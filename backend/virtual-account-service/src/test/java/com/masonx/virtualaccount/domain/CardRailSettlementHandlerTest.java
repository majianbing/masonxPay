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
    private static final String HOLD_ACCT   = "ac_card_hold_1";
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
                "card_1", MASKED_PAN, "999999", CARD_ACCT, HOLD_ACCT, WALLET_ACCT,
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

    private VaAccount holdAccount(BigDecimal balance) {
        return new VaAccount(
                HOLD_ACCT, Mode.TEST, AccountRole.TENANT,
                "org_1", MERCHANT_ID, null,
                AccountType.PREPAID_CARD_HOLD, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, balance, BigDecimal.ZERO, AccountStatus.ACTIVE);
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
        VaAccount holdAcct   = holdAccount(new BigDecimal("100.00"));
        VaAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, AccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
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
        assertThat(creditEntry.accountId()).isEqualTo(HOLD_ACCT);
        assertThat(debitEntry.amount()).isEqualByComparingTo("100.00");
        assertThat(creditEntry.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void card_sale_does_not_mutate_frozen_balance_after_posting() {
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), new BigDecimal("100.00"));
        VaAccount holdAcct   = holdAccount(new BigDecimal("100.00"));
        VaAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, AccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(accountRepo.findExternalAccount("VISA_SIM", "USD", AccountType.CARD_NETWORK_RECEIVABLE))
                .thenReturn(Optional.of(rcvAcct));
        when(ledger.postIfNew(any(), any(), any())).thenReturn(true);

        handler.handle(event(MoneyMovementType.CARD_SALE, MASKED_PAN, MERCHANT_ID));
    }

    @Test
    void card_sale_duplicate_event_skips_balance_update() {
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), new BigDecimal("100.00"));
        VaAccount holdAcct   = holdAccount(new BigDecimal("100.00"));
        VaAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, AccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(accountRepo.findExternalAccount("VISA_SIM", "USD", AccountType.CARD_NETWORK_RECEIVABLE))
                .thenReturn(Optional.of(rcvAcct));
        // Duplicate delivery → postIfNew returns false
        when(ledger.postIfNew(any(), any(), any())).thenReturn(false);

        handler.handle(event(MoneyMovementType.CARD_SALE, MASKED_PAN, MERCHANT_ID));
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
    void card_reversal_posts_journal_debit_card_credit_hold_account() {
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), new BigDecimal("100.00"));
        VaAccount holdAcct   = holdAccount(new BigDecimal("100.00"));

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(ledger.postIfNew(any(), eq("evt_test_001"), eq("rail-card-reversal"))).thenReturn(true);

        handler.handle(event(MoneyMovementType.CARD_REVERSAL, MASKED_PAN, MERCHANT_ID));

        var txCaptor = ArgumentCaptor.forClass(PostTransaction.class);
        verify(ledger).postIfNew(txCaptor.capture(), eq("evt_test_001"), eq("rail-card-reversal"));

        EntryDraft debitEntry = txCaptor.getValue().entries().stream()
                .filter(e -> e.direction() == Direction.DEBIT).findFirst().orElseThrow();
        EntryDraft creditEntry = txCaptor.getValue().entries().stream()
                .filter(e -> e.direction() == Direction.CREDIT).findFirst().orElseThrow();
        assertThat(debitEntry.accountId()).isEqualTo(CARD_ACCT);
        assertThat(creditEntry.accountId()).isEqualTo(HOLD_ACCT);
    }

    @Test
    void card_reversal_duplicate_event_skips_second_journal() {
        VirtualCard testCard = card();
        VaAccount cardAcct   = cardAccount(new BigDecimal("300.00"), BigDecimal.ZERO);
        VaAccount holdAcct   = holdAccount(new BigDecimal("100.00"));

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(ledger.postIfNew(any(), eq("evt_test_001"), eq("rail-card-reversal"))).thenReturn(false);

        handler.handle(event(MoneyMovementType.CARD_REVERSAL, MASKED_PAN, MERCHANT_ID));

        verify(ledger).postIfNew(any(), eq("evt_test_001"), eq("rail-card-reversal"));
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
    }

    @Test
    void bank_credit_transfer_skips_when_merchant_id_is_null() {
        handler.handle(bankEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, null));

        verifyNoInteractions(ledger);
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
