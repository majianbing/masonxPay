package com.masonx.virtualaccount.domain;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.contracts.EventEnvelope;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.LedgerAccountRepository;
import com.masonx.virtualaccount.domain.ledger.AccountingEntryDraft;
import com.masonx.virtualaccount.domain.ledger.LedgerFacade;
import com.masonx.virtualaccount.domain.ledger.LedgerPostingCommand;
import com.masonx.virtualaccount.domain.ledger.posting.CardSettlementPostingRule;
import com.masonx.virtualaccount.domain.ledger.posting.RailSettlementPostingRule;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CardRailSettlementHandlerTest {

    @Mock VirtualCardRepository virtualCardRepo;
    @Mock LedgerAccountRepository     accountRepo;
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
        SnowflakeIdGenerator idGen = new SnowflakeIdGenerator(0);
        handler = new CardRailSettlementHandler(
                virtualCardRepo, accountRepo, ledger,
                new CardSettlementPostingRule(idGen), new RailSettlementPostingRule(idGen));
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

    private LedgerAccount cardAccount(BigDecimal balance) {
        return new LedgerAccount(
                CARD_ACCT, Mode.TEST, LedgerAccountRole.TENANT,
                "org_1", MERCHANT_ID, null,
                LedgerAccountType.PREPAID_CARD, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, balance, LedgerAccountStatus.ACTIVE);
    }

    private LedgerAccount holdAccount(BigDecimal balance) {
        return new LedgerAccount(
                HOLD_ACCT, Mode.TEST, LedgerAccountRole.TENANT,
                "org_1", MERCHANT_ID, null,
                LedgerAccountType.PREPAID_CARD_HOLD, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, balance, LedgerAccountStatus.ACTIVE);
    }

    private LedgerAccount receivableAccount(String id, LedgerAccountType type, String providerId) {
        return new LedgerAccount(
                id, Mode.TEST, LedgerAccountRole.EXTERNAL,
                null, null, providerId, type, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE);
    }

    private LedgerAccount walletAccount() {
        return new LedgerAccount(
                WALLET_ACCT, Mode.TEST, LedgerAccountRole.TENANT,
                "org_1", MERCHANT_ID, null,
                LedgerAccountType.WALLET, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, new BigDecimal("500.00"), LedgerAccountStatus.ACTIVE);
    }

    // ── CARD_SALE ─────────────────────────────────────────────────────────────

    @Test
    void card_sale_posts_journal_debit_receivable_credit_card_account() {
        VirtualCard testCard = card();
        LedgerAccount cardAcct   = cardAccount(new BigDecimal("300.00"));
        LedgerAccount holdAcct   = holdAccount(new BigDecimal("100.00"));
        LedgerAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, LedgerAccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(accountRepo.findExternalAccount("VISA_SIM", "USD", LedgerAccountType.CARD_NETWORK_RECEIVABLE))
                .thenReturn(Optional.of(rcvAcct));
        when(ledger.postAllIfNew(any(), eq("evt_test_001"), eq("rail-card-sale"))).thenReturn(true);

        handler.handle(event(MoneyMovementType.CARD_SALE, MASKED_PAN, MERCHANT_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledger).postAllIfNew(txCaptor.capture(), eq("evt_test_001"), eq("rail-card-sale"));

        LedgerPostingCommand tx = txCaptor.getValue().get(0);
        assertThat(tx.entries()).hasSize(2);

        AccountingEntryDraft debitEntry  = tx.entries().stream()
                .filter(e -> e.direction() == Direction.DEBIT).findFirst().orElseThrow();
        AccountingEntryDraft creditEntry = tx.entries().stream()
                .filter(e -> e.direction() == Direction.CREDIT).findFirst().orElseThrow();

        assertThat(debitEntry.ledgerAccountId()).isEqualTo(RECEIVABLE_CARD_ACCT);
        assertThat(creditEntry.ledgerAccountId()).isEqualTo(HOLD_ACCT);
        assertThat(debitEntry.amount()).isEqualByComparingTo("100.00");
        assertThat(creditEntry.amount()).isEqualByComparingTo("100.00");
    }

    @Test
    void card_sale_does_not_mutate_frozen_balance_after_posting() {
        VirtualCard testCard = card();
        LedgerAccount cardAcct   = cardAccount(new BigDecimal("300.00"));
        LedgerAccount holdAcct   = holdAccount(new BigDecimal("100.00"));
        LedgerAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, LedgerAccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(accountRepo.findExternalAccount("VISA_SIM", "USD", LedgerAccountType.CARD_NETWORK_RECEIVABLE))
                .thenReturn(Optional.of(rcvAcct));
        when(ledger.postAllIfNew(any(), any(), any())).thenReturn(true);

        handler.handle(event(MoneyMovementType.CARD_SALE, MASKED_PAN, MERCHANT_ID));
    }

    @Test
    void card_sale_duplicate_event_skips_balance_update() {
        VirtualCard testCard = card();
        LedgerAccount cardAcct   = cardAccount(new BigDecimal("300.00"));
        LedgerAccount holdAcct   = holdAccount(new BigDecimal("100.00"));
        LedgerAccount rcvAcct    = receivableAccount(RECEIVABLE_CARD_ACCT, LedgerAccountType.CARD_NETWORK_RECEIVABLE, "VISA_SIM");

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(accountRepo.findExternalAccount("VISA_SIM", "USD", LedgerAccountType.CARD_NETWORK_RECEIVABLE))
                .thenReturn(Optional.of(rcvAcct));
        // Duplicate delivery -> postAllIfNew returns false.
        when(ledger.postAllIfNew(any(), any(), any())).thenReturn(false);

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
        LedgerAccount cardAcct   = cardAccount(new BigDecimal("300.00"));
        LedgerAccount holdAcct   = holdAccount(new BigDecimal("100.00"));

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(ledger.postAllIfNew(any(), eq("evt_test_001"), eq("rail-card-reversal"))).thenReturn(true);

        handler.handle(event(MoneyMovementType.CARD_REVERSAL, MASKED_PAN, MERCHANT_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledger).postAllIfNew(txCaptor.capture(), eq("evt_test_001"), eq("rail-card-reversal"));

        LedgerPostingCommand tx = txCaptor.getValue().get(0);
        AccountingEntryDraft debitEntry = tx.entries().stream()
                .filter(e -> e.direction() == Direction.DEBIT).findFirst().orElseThrow();
        AccountingEntryDraft creditEntry = tx.entries().stream()
                .filter(e -> e.direction() == Direction.CREDIT).findFirst().orElseThrow();
        assertThat(debitEntry.ledgerAccountId()).isEqualTo(CARD_ACCT);
        assertThat(creditEntry.ledgerAccountId()).isEqualTo(HOLD_ACCT);
    }

    @Test
    void card_reversal_duplicate_event_skips_second_journal() {
        VirtualCard testCard = card();
        LedgerAccount cardAcct   = cardAccount(new BigDecimal("300.00"));
        LedgerAccount holdAcct   = holdAccount(new BigDecimal("100.00"));

        when(virtualCardRepo.findActiveByMaskedPan(MASKED_PAN)).thenReturn(Optional.of(testCard));
        when(accountRepo.findById(CARD_ACCT)).thenReturn(Optional.of(cardAcct));
        when(accountRepo.findById(HOLD_ACCT)).thenReturn(Optional.of(holdAcct));
        when(ledger.postAllIfNew(any(), eq("evt_test_001"), eq("rail-card-reversal"))).thenReturn(false);

        handler.handle(event(MoneyMovementType.CARD_REVERSAL, MASKED_PAN, MERCHANT_ID));

        verify(ledger).postAllIfNew(any(), eq("evt_test_001"), eq("rail-card-reversal"));
    }

    // ── BANK_CREDIT_TRANSFER ─────────────────────────────────────────────────

    @Test
    void bank_credit_transfer_posts_receivable_debit_and_wallet_credit() {
        LedgerAccount wallet  = walletAccount();
        LedgerAccount bankRcv = receivableAccount(RECEIVABLE_BANK_ACCT, LedgerAccountType.BANK_RAIL_RECEIVABLE, "SEPA_SIM");

        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", LedgerAccountType.WALLET))
                .thenReturn(Optional.of(wallet));
        when(accountRepo.findExternalAccount("SEPA_SIM", "USD", LedgerAccountType.BANK_RAIL_RECEIVABLE))
                .thenReturn(Optional.of(bankRcv));
        when(ledger.postAllIfNew(any(), eq("evt_bank_001"), eq("rail-bank-settle"))).thenReturn(true);

        handler.handle(bankEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, MERCHANT_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledger).postAllIfNew(txCaptor.capture(), eq("evt_bank_001"), eq("rail-bank-settle"));

        LedgerPostingCommand tx = txCaptor.getValue().get(0);
        assertThat(tx.entries()).hasSize(2);

        AccountingEntryDraft debitEntry  = tx.entries().stream()
                .filter(e -> e.direction() == Direction.DEBIT).findFirst().orElseThrow();
        AccountingEntryDraft creditEntry = tx.entries().stream()
                .filter(e -> e.direction() == Direction.CREDIT).findFirst().orElseThrow();

        assertThat(debitEntry.ledgerAccountId()).isEqualTo(RECEIVABLE_BANK_ACCT);
        assertThat(creditEntry.ledgerAccountId()).isEqualTo(WALLET_ACCT);
        assertThat(debitEntry.amount()).isEqualByComparingTo("200.00");
        assertThat(creditEntry.amount()).isEqualByComparingTo("200.00");
    }

    @Test
    void bank_credit_transfer_duplicate_event_does_not_post_twice() {
        LedgerAccount wallet  = walletAccount();
        LedgerAccount bankRcv = receivableAccount(RECEIVABLE_BANK_ACCT, LedgerAccountType.BANK_RAIL_RECEIVABLE, "SEPA_SIM");

        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", LedgerAccountType.WALLET))
                .thenReturn(Optional.of(wallet));
        when(accountRepo.findExternalAccount("SEPA_SIM", "USD", LedgerAccountType.BANK_RAIL_RECEIVABLE))
                .thenReturn(Optional.of(bankRcv));
        when(ledger.postAllIfNew(any(), any(), any())).thenReturn(false);

        handler.handle(bankEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, MERCHANT_ID));

        verify(ledger, times(1)).postAllIfNew(any(), any(), any());
    }

    @Test
    void bank_credit_transfer_skips_when_merchant_id_is_null() {
        handler.handle(bankEvent(MoneyMovementType.BANK_CREDIT_TRANSFER, null));

        verifyNoInteractions(ledger);
    }

    // ── BANK_RETURN ───────────────────────────────────────────────────────────

    @Test
    void bank_return_posts_wallet_debit_and_receivable_credit() {
        LedgerAccount wallet  = walletAccount();
        LedgerAccount bankRcv = receivableAccount(RECEIVABLE_BANK_ACCT, LedgerAccountType.BANK_RAIL_RECEIVABLE, "SEPA_SIM");

        when(accountRepo.findTenantAccount(MERCHANT_ID, Mode.TEST, "USD", LedgerAccountType.WALLET))
                .thenReturn(Optional.of(wallet));
        when(accountRepo.findExternalAccount("SEPA_SIM", "USD", LedgerAccountType.BANK_RAIL_RECEIVABLE))
                .thenReturn(Optional.of(bankRcv));
        when(ledger.postAllIfNew(any(), eq("evt_bank_001"), eq("rail-bank-return"))).thenReturn(true);

        handler.handle(bankEvent(MoneyMovementType.BANK_RETURN, MERCHANT_ID));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerPostingCommand>> txCaptor = ArgumentCaptor.forClass(List.class);
        verify(ledger).postAllIfNew(txCaptor.capture(), eq("evt_bank_001"), eq("rail-bank-return"));

        LedgerPostingCommand tx = txCaptor.getValue().get(0);
        assertThat(tx.entries()).hasSize(2);

        AccountingEntryDraft debitEntry  = tx.entries().stream()
                .filter(e -> e.direction() == Direction.DEBIT).findFirst().orElseThrow();
        AccountingEntryDraft creditEntry = tx.entries().stream()
                .filter(e -> e.direction() == Direction.CREDIT).findFirst().orElseThrow();

        // BANK_RETURN reverses the settlement: DR wallet / CR receivable
        assertThat(debitEntry.ledgerAccountId()).isEqualTo(WALLET_ACCT);
        assertThat(creditEntry.ledgerAccountId()).isEqualTo(RECEIVABLE_BANK_ACCT);
        assertThat(debitEntry.amount()).isEqualByComparingTo("200.00");
        assertThat(creditEntry.amount()).isEqualByComparingTo("200.00");
    }
}
