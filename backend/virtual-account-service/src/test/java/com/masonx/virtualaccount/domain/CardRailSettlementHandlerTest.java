package com.masonx.virtualaccount.domain;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.contracts.EventEnvelope;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.AccountRepository;
import com.masonx.virtualaccount.domain.ledger.EntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.PostTransaction;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardRailSettlementHandlerTest {

    @Mock VirtualCardRepository virtualCardRepo;
    @Mock AccountRepository     accountRepo;
    @Mock LedgerFacade          ledger;

    CardRailSettlementHandler handler;

    private static final String MASKED_PAN  = "999999****1234";
    private static final String MERCHANT_ID = "mer_abc";
    private static final String CARD_ACCT   = "ac_card_1";
    private static final String WALLET_ACCT = "ac_wallet_1";
    private static final String RECEIVABLE_CARD_ACCT = "ac_rcv_visa";
    private static final String RECEIVABLE_BANK_ACCT = "ac_rcv_sepa";

    @BeforeEach
    void setUp() {
        handler = new CardRailSettlementHandler(
                virtualCardRepo, accountRepo, ledger, new SnowflakeIdGenerator(0));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RailSettlementEvent event(MoneyMovementType type, String maskedPan, String merchantId) {
        var envelope = new EventEnvelope(
                "evt_test_001", RailSettlementEvent.TYPE, 2, Instant.now(), "corr_1", null);
        return new RailSettlementEvent(
                envelope, "pay_001", PaymentRail.CARD_ISO8583, type,
                "USD", new BigDecimal("100.00"),
                null, null, "VISA_SIM", Instant.now(), merchantId, maskedPan);
    }

    private RailSettlementEvent bankEvent(MoneyMovementType type, String merchantId) {
        var envelope = new EventEnvelope(
                "evt_bank_001", RailSettlementEvent.TYPE, 2, Instant.now(), "corr_2", null);
        return new RailSettlementEvent(
                envelope, "pay_bank_001", PaymentRail.BANK_ISO20022, type,
                "USD", new BigDecimal("200.00"),
                null, null, "SEPA_SIM", Instant.now(), merchantId, null);
    }

    private VirtualCard card() {
        return new VirtualCard(
                "card_1", MASKED_PAN, "999999", CARD_ACCT, WALLET_ACCT,
                VirtualCardStatus.ACTIVE, new BigDecimal("500.00"), "USD",
                null, Instant.now(), Instant.now());
    }

    private VaAccount cardAccount(BigDecimal balance, BigDecimal frozen) {
        return new VaAccount(
                CARD_ACCT, Mode.TEST, AccountRole.TENANT,
                "org_1", MERCHANT_ID, null,
                AccountType.PREPAID_CARD, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, balance, frozen, AccountStatus.ACTIVE);
    }

    private VaAccount receivableAccount(String id, AccountType type, String providerId) {
        return new VaAccount(
                id, Mode.TEST, AccountRole.EXTERNAL,
                null, null, providerId, type, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, BigDecimal.ZERO, AccountStatus.ACTIVE);
    }

    private VaAccount walletAccount() {
        return new VaAccount(
                WALLET_ACCT, Mode.TEST, AccountRole.TENANT,
                "org_1", MERCHANT_ID, null,
                AccountType.WALLET, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, new BigDecimal("500.00"), BigDecimal.ZERO, AccountStatus.ACTIVE);
    }

    // ── CARD_SALE ─────────────────────────────────────────────────────────────

    @Test
    void card_sale_posts_journal_debit_receivable_credit_card_account() {
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), new BigDecimal("100.00"));
        VaAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, AccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findByIdForUpdate(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findExternalAccount("VISA_SIM", "USD", AccountType.CARD_NETWORK_RECEIVABLE))
                .thenReturn(Optional.of(rcvAcct));
        when(ledger.postIfNew(any(), eq("evt_test_001"), eq("rail-card-sale"))).thenReturn(true);

        handler.handle(event(MoneyMovementType.CARD_SALE, MASKED_PAN, MERCHANT_ID));

        var txCaptor = ArgumentCaptor.forClass(PostTransaction.class);
        verify(ledger).postIfNew(txCaptor.capture(), eq("evt_test_001"), eq("rail-card-sale"));

        PostTransaction tx = txCaptor.getValue();
        assertThat(tx.entries()).hasSize(2);

        EntryDraft debitEntry  = tx.entries().stream()
                .filter(e -> e.direction() == Direction.DEBIT).findFirst().orElseThrow();
        EntryDraft creditEntry = tx.entries().stream()
                .filter(e -> e.direction() == Direction.CREDIT).findFirst().orElseThrow();

        assertThat(debitEntry.accountId()).isEqualTo(RECEIVABLE_CARD_ACCT);
        assertThat(creditEntry.accountId()).isEqualTo(CARD_ACCT);
        assertThat(debitEntry.amount()).isEqualByComparingTo("100.00");
        assertThat(creditEntry.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void card_sale_releases_frozen_balance_after_posting() {
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), new BigDecimal("100.00"));
        VaAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, AccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findByIdForUpdate(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findExternalAccount("VISA_SIM", "USD", AccountType.CARD_NETWORK_RECEIVABLE))
                .thenReturn(Optional.of(rcvAcct));
        when(ledger.postIfNew(any(), any(), any())).thenReturn(true);

        handler.handle(event(MoneyMovementType.CARD_SALE, MASKED_PAN, MERCHANT_ID));

        // frozen 100.00 − sale 100.00 = 0.00
        verify(accountRepo).updateBalance(eq(CARD_ACCT), eq(new BigDecimal("300.00")),
                argThat(v -> v.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void card_sale_duplicate_event_skips_balance_update() {
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), new BigDecimal("100.00"));
        VaAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, AccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findByIdForUpdate(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findExternalAccount("VISA_SIM", "USD", AccountType.CARD_NETWORK_RECEIVABLE))
                .thenReturn(Optional.of(rcvAcct));
        // Duplicate delivery → postIfNew returns false
        when(ledger.postIfNew(any(), any(), any())).thenReturn(false);

        handler.handle(event(MoneyMovementType.CARD_SALE, MASKED_PAN, MERCHANT_ID));

        verify(accountRepo, never()).updateBalance(any(), any(), any());
    }

    @Test
    void card_sale_skips_silently_when_masked_pan_is_null() {
        handler.handle(event(MoneyMovementType.CARD_SALE, null, MERCHANT_ID));

        verifyNoInteractions(accountRepo, ledger);
    }

    @Test
    void card_sale_skips_silently_when_card_not_found() {
        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.empty());

        handler.handle(event(MoneyMovementType.CARD_SALE, MASKED_PAN, MERCHANT_ID));

        verifyNoInteractions(accountRepo, ledger);
    }

    // ── CARD_REVERSAL ─────────────────────────────────────────────────────────

    @Test
    void card_reversal_releases_frozen_balance_without_ledger_entry() {
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), new BigDecimal("100.00"));

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findByIdForUpdate(CARD_ACCT)).thenReturn(Optional.of(cardAcct));

        handler.handle(event(MoneyMovementType.CARD_REVERSAL, MASKED_PAN, MERCHANT_ID));

        // No ledger entry — auth never posted one
        verifyNoInteractions(ledger);
        // frozen 100.00 − reversal 100.00 = 0.00
        verify(accountRepo).updateBalance(eq(CARD_ACCT), eq(new BigDecimal("300.00")),
                argThat(v -> v.compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void card_reversal_clamps_frozen_balance_to_zero_if_already_released() {
        // Defensive: frozen balance less than reversal amount (e.g. partial earlier release)
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), new BigDecimal("50.00"));

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findByIdForUpdate(CARD_ACCT)).thenReturn(Optional.of(cardAcct));

        handler.handle(event(MoneyMovementType.CARD_REVERSAL, MASKED_PAN, MERCHANT_ID));

        // 50.00 − 100.00 = −50.00 → clamped to 0.00
        verify(accountRepo).updateBalance(eq(CARD_ACCT), eq(new BigDecimal("300.00")),
                argThat(v -> v.compareTo(BigDecimal.ZERO) == 0));
    }

    // ── BANK_CREDIT_TRANSFER ─────────────────────────────────────────────────

    @Test
    void bank_credit_transfer_posts_receivable_debit_and_wallet_credit() {
        VaAccount wallet  = walletAccount();
        VaAccount bankRcv = receivableAccount(RECEIVABLE_BANK_ACCT, AccountType.BANK_RAIL_RECEIVABLE, "SEPA_SIM");

        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", AccountType.WALLET))
                .thenReturn(Optional.of(wallet));
        when(accountRepo.findExternalAccount("SEPA_SIM", "USD", AccountType.BANK_RAIL_RECEIVABLE))
                .thenReturn(Optional.of(bankRcv));
        when(ledger.postIfNew(any(), eq("evt_bank_001"), eq("rail-bank-settle"))).thenReturn(true);

        handler.handle(bankEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, MERCHANT_ID));

        var txCaptor = ArgumentCaptor.forClass(PostTransaction.class);
        verify(ledger).postIfNew(txCaptor.capture(), eq("evt_bank_001"), eq("rail-bank-settle"));

        PostTransaction tx = txCaptor.getValue();
        assertThat(tx.entries()).hasSize(2);

        EntryDraft debitEntry  = tx.entries().stream()
                .filter(e -> e.direction() == Direction.DEBIT).findFirst().orElseThrow();
        EntryDraft creditEntry = tx.entries().stream()
                .filter(e -> e.direction() == Direction.CREDIT).findFirst().orElseThrow();

        assertThat(debitEntry.accountId()).isEqualTo(RECEIVABLE_BANK_ACCT);
        assertThat(creditEntry.accountId()).isEqualTo(WALLET_ACCT);
        assertThat(debitEntry.amount()).isEqualByComparingTo("200.00");
        assertThat(creditEntry.amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void bank_credit_transfer_duplicate_event_does_not_post_twice() {
        VaAccount wallet  = walletAccount();
        VaAccount bankRcv = receivableAccount(RECEIVABLE_BANK_ACCT, AccountType.BANK_RAIL_RECEIVABLE, "SEPA_SIM");

        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", AccountType.WALLET))
                .thenReturn(Optional.of(wallet));
        when(accountRepo.findExternalAccount("SEPA_SIM", "USD", AccountType.BANK_RAIL_RECEIVABLE))
                .thenReturn(Optional.of(bankRcv));
        when(ledger.postIfNew(any(), any(), any())).thenReturn(false);

        handler.handle(bankEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, MERCHANT_ID));

        verify(ledger, times(1)).postIfNew(any(), any(), any());
        verify(accountRepo, never()).updateBalance(any(), any(), any());
    }

    @Test
    void bank_credit_transfer_skips_when_merchant_id_is_null() {
        handler.handle(bankEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, null));

        verifyNoInteractions(ledger);
        verify(accountRepo, never()).updateBalance(any(), any(), any());
    }

    // ── BANK_RETURN ───────────────────────────────────────────────────────────

    @Test
    void bank_return_posts_wallet_debit_and_receivable_credit() {
        VaAccount wallet  = walletAccount();
        VaAccount bankRcv = receivableAccount(RECEIVABLE_BANK_ACCT, AccountType.BANK_RAIL_RECEIVABLE, "SEPA_SIM");

        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", AccountType.WALLET))
                .thenReturn(Optional.of(wallet));
        when(accountRepo.findExternalAccount("SEPA_SIM", "USD", AccountType.BANK_RAIL_RECEIVABLE))
                .thenReturn(Optional.of(bankRcv));
        when(ledger.postIfNew(any(), eq("evt_bank_001"), eq("rail-bank-return"))).thenReturn(true);

        handler.handle(bankEvent(MoneyMovementType.BANK_RETURN, MERCHANT_ID));

        var txCaptor = ArgumentCaptor.forClass(PostTransaction.class);
        verify(ledger).postIfNew(txCaptor.capture(), eq("evt_bank_001"), eq("rail-bank-return"));

        PostTransaction tx = txCaptor.getValue();
        assertThat(tx.entries()).hasSize(2);

        EntryDraft debitEntry  = tx.entries().stream()
                .filter(e -> e.direction() == Direction.DEBIT).findFirst().orElseThrow();
        EntryDraft creditEntry = tx.entries().stream()
                .filter(e -> e.direction() == Direction.CREDIT).findFirst().orElseThrow();

        // BANK_RETURN reverses the settlement: DR wallet / CR receivable
        assertThat(debitEntry.accountId()).isEqualTo(WALLET_ACCT);
        assertThat(creditEntry.accountId()).isEqualTo(RECEIVABLE_BANK_ACCT);
        assertThat(debitEntry.amount()).isEqualByComparingTo("200.00");
        assertThat(creditEntry.amount()).isEqualByComparingTo("200.00");
    }
}
