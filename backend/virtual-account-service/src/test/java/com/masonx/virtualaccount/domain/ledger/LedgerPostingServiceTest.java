package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.validator.AssetConsistencyValidator;
import com.masonx.virtualaccount.domain.ledger.validator.InsufficientBalanceValidator;
import com.masonx.virtualaccount.domain.ledger.validator.NetZeroValidator;
import com.masonx.virtualaccount.domain.po.LedgerEntry;
import com.masonx.virtualaccount.domain.po.VaAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerPostingServiceTest {

    @Mock AccountRepository       accountRepo;
    @Mock LedgerEntryRepository   entryRepo;
    @Mock TransactionRepository   txRepo;
    @Mock BalanceSignatureService  signatureService;

    LedgerPostingService service;

    @BeforeEach
    void setUp() {
        service = new LedgerPostingService(
                accountRepo, entryRepo, txRepo, signatureService,
                new SnowflakeIdGenerator(0),
                List.of(new AssetConsistencyValidator(), new NetZeroValidator()),
                List.of(new InsufficientBalanceValidator()));
    }

    private PostTransaction tx(List<EntryDraft> entries) {
        return new PostTransaction("tx_1", entries,
                TransactionType.INTERNAL, null, null,
                LocalDate.of(2026, 1, 1), Mode.LIVE, null, null);
    }

    // --- helpers ---

    private VaAccount cashAccount(String id, BigDecimal balance) {
        return new VaAccount(id, Mode.LIVE, AccountRole.TENANT,
                "org_1", "mer_1", null,
                AccountType.CASH, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, balance, BigDecimal.ZERO, AccountStatus.ACTIVE);
    }

    private VaAccount externalAccount(String id) {
        return new VaAccount(id, Mode.LIVE, AccountRole.EXTERNAL,
                null, null, "provider_stripe",
                AccountType.CLEARING, "USD", AssetClass.FIAT, 2,
                NormalBalance.CREDIT, BigDecimal.ZERO, BigDecimal.ZERO, AccountStatus.ACTIVE);
    }

    private EntryDraft credit(String accountId, String amount) {
        return new EntryDraft(accountId, Direction.CREDIT,
                new BigDecimal(amount), "USD", "evt_1");
    }

    private EntryDraft debit(String accountId, String amount) {
        return new EntryDraft(accountId, Direction.DEBIT,
                new BigDecimal(amount), "USD", "evt_1");
    }

    /** A ChainHead whose verify() call will pass when mocked to return true. */
    private ChainHead chainHead(long seq, String sig) {
        return new ChainHead(seq, new BigDecimal("100.00"), Direction.DEBIT,
                new BigDecimal("100.00"), BigDecimal.ZERO, "tx_prev",
                ChainAnchor.GENESIS_SIGNATURE, sig);
    }

    // --- validation tests ---

    @Test
    void rejects_unbalanced_transaction() {
        var tx = tx(List.of(
                credit("ac_tenant", "100.00"),
                debit("ac_external", "90.00")));  // 100 != 90

        assertThatThrownBy(() -> service.post(tx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("balanced");
    }

    @Test
    void rejects_mixed_asset_transaction() {
        var tx = tx(List.of(
                new EntryDraft("ac_1", Direction.DEBIT,  new BigDecimal("100"), "USD", "evt_1"),
                new EntryDraft("ac_2", Direction.CREDIT, new BigDecimal("100"), "BTC", "evt_1")));

        assertThatThrownBy(() -> service.post(tx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("asset");
    }

    @Test
    void rejects_frozen_account() {
        var frozen = new VaAccount("ac_1", Mode.LIVE, AccountRole.TENANT,
                "org_1", "mer_1", null, AccountType.CASH, "USD", AssetClass.FIAT, 2,
                NormalBalance.DEBIT, new BigDecimal("200"), BigDecimal.ZERO, AccountStatus.FROZEN);

        // "ac_1" sorts before "ac_ext" — it's locked first and throws before ac_ext is touched
        when(accountRepo.findByIdForUpdate("ac_1")).thenReturn(Optional.of(frozen));

        var tx = tx(List.of(
                debit("ac_1", "100.00"), credit("ac_ext", "100.00")));

        assertThatThrownBy(() -> service.post(tx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void rejects_posting_that_would_go_negative() {
        when(accountRepo.findByIdForUpdate("ac_ext")).thenReturn(
                Optional.of(externalAccount("ac_ext")));
        when(accountRepo.findByIdForUpdate("ac_tenant")).thenReturn(
                Optional.of(cashAccount("ac_tenant", new BigDecimal("50.00"))));

        // CASH is DEBIT-normal: CREDIT reduces the balance. Crediting 100 from a 50 balance → -50.
        // entryValidators fire before chain verification, so findLastChainHead is never called.
        var tx = tx(List.of(
                credit("ac_tenant", "100.00"), debit("ac_ext", "100.00")));

        assertThatThrownBy(() -> service.post(tx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("negative");
    }

    // --- posting correctness ---

    @Test
    void posts_balanced_transaction_and_updates_balances() {
        when(accountRepo.findByIdForUpdate("ac_tenant")).thenReturn(
                Optional.of(cashAccount("ac_tenant", BigDecimal.ZERO)));
        when(accountRepo.findByIdForUpdate("ac_ext")).thenReturn(
                Optional.of(externalAccount("ac_ext")));
        when(entryRepo.findLastChainHead(any())).thenReturn(Optional.empty());
        when(signatureService.compute(any())).thenReturn("sig_abc");

        // Settlement: provider clears $100 to merchant.
        // CASH is DEBIT-normal — money in = DEBIT merchant, CREDIT external.
        var tx = tx(List.of(
                debit("ac_tenant",  "100.00"),
                credit("ac_ext",    "100.00")));

        service.post(tx);

        verify(entryRepo, times(2)).insert(any());
        verify(accountRepo, times(2)).updateLedgerBalance(any(), any(), any());
    }

    @Test
    void entry_seq_starts_at_1_when_no_prior_entries() {
        when(accountRepo.findByIdForUpdate("ac_tenant")).thenReturn(
                Optional.of(cashAccount("ac_tenant", BigDecimal.ZERO)));
        when(accountRepo.findByIdForUpdate("ac_ext")).thenReturn(
                Optional.of(externalAccount("ac_ext")));
        when(entryRepo.findLastChainHead(any())).thenReturn(Optional.empty());
        when(signatureService.compute(any())).thenReturn("sig");

        service.post(tx(List.of(
                debit("ac_tenant", "100.00"), credit("ac_ext", "100.00"))));

        var captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepo, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(e -> e.entrySeq() == 1L);
    }

    @Test
    void uses_genesis_signature_for_first_entry() {
        // Two distinct accounts with no prior entries — both must chain to GENESIS.
        when(accountRepo.findByIdForUpdate("ac_tenant")).thenReturn(
                Optional.of(cashAccount("ac_tenant", BigDecimal.ZERO)));
        when(accountRepo.findByIdForUpdate("ac_ext")).thenReturn(
                Optional.of(externalAccount("ac_ext")));
        when(entryRepo.findLastChainHead(any())).thenReturn(Optional.empty());
        when(signatureService.compute(any())).thenReturn("sig");

        service.post(tx(List.of(
                debit("ac_tenant", "100.00"), credit("ac_ext", "100.00"))));

        var sigCaptor = ArgumentCaptor.forClass(SignatureInput.class);
        verify(signatureService, atLeastOnce()).compute(sigCaptor.capture());
        assertThat(sigCaptor.getAllValues())
                .allMatch(s -> s.prevSignature().equals(ChainAnchor.GENESIS_SIGNATURE));
    }

    @Test
    void chains_prev_signature_from_last_entry() {
        // ac_tenant has prior entries (chain head with balanceAfter=100 matches account balance).
        // ac_ext is fresh (no prior entries, balance=0).
        // Verifies that ac_tenant's new entry uses the chain head's signature as prevSignature.
        String prevSig = "prev-sig-abc";
        ChainHead tenantHead = chainHead(5L, prevSig);  // balanceAfter=100

        when(accountRepo.findByIdForUpdate("ac_tenant")).thenReturn(
                Optional.of(cashAccount("ac_tenant", new BigDecimal("100.00"))));
        when(accountRepo.findByIdForUpdate("ac_ext")).thenReturn(
                Optional.of(externalAccount("ac_ext")));
        when(entryRepo.findLastChainHead("ac_tenant")).thenReturn(Optional.of(tenantHead));
        when(entryRepo.findLastChainHead("ac_ext")).thenReturn(Optional.empty());
        when(signatureService.verify(any(), eq(prevSig))).thenReturn(true);
        when(signatureService.compute(any())).thenReturn("new-sig");

        service.post(tx(List.of(
                debit("ac_tenant", "50.00"), credit("ac_ext", "50.00"))));

        var captor = ArgumentCaptor.forClass(SignatureInput.class);
        verify(signatureService, atLeastOnce()).compute(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(s -> s.prevSignature().equals(prevSig));
    }

    @Test
    void rejects_posting_when_va_account_balance_tampered() {
        // Simulate: attacker ran UPDATE va_account SET balance = 0 but left va_ledger_entry intact.
        // account.balance() = 0, but last entry balance_after = 100 → mismatch caught before HMAC check.
        ChainHead head = new ChainHead(1L, new BigDecimal("100.00"), Direction.DEBIT,
                new BigDecimal("100.00"), BigDecimal.ZERO, "tx_prev",
                ChainAnchor.GENESIS_SIGNATURE, "sig");

        when(accountRepo.findByIdForUpdate("ac_tenant")).thenReturn(
                Optional.of(cashAccount("ac_tenant", BigDecimal.ZERO)));   // tampered balance
        when(accountRepo.findByIdForUpdate("ac_ext")).thenReturn(
                Optional.of(externalAccount("ac_ext")));
        // Exception fires on ac_tenant before ac_ext is reached — no ac_ext chain stub needed.
        when(entryRepo.findLastChainHead("ac_tenant")).thenReturn(Optional.of(head));

        var tx = tx(List.of(
                debit("ac_tenant", "50.00"), credit("ac_ext", "50.00")));

        assertThatThrownBy(() -> service.post(tx))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo("VA_BALANCE_MISMATCH"));

        verify(entryRepo, never()).insert(any());
    }

    @Test
    void rejects_posting_when_chain_head_is_tampered() {
        // account.balance matches last entry (balance cross-check passes),
        // but HMAC recomputation fails → entry row was modified directly.
        ChainHead tamperedHead = chainHead(3L, "stored-sig");

        when(accountRepo.findByIdForUpdate("ac_tenant")).thenReturn(
                Optional.of(cashAccount("ac_tenant", new BigDecimal("100.00"))));
        when(accountRepo.findByIdForUpdate("ac_ext")).thenReturn(
                Optional.of(externalAccount("ac_ext")));
        when(entryRepo.findLastChainHead(any())).thenReturn(Optional.of(tamperedHead));
        // Signature recomputation does not match stored signature → tampering detected.
        when(signatureService.verify(any(), eq("stored-sig"))).thenReturn(false);

        var tx = tx(List.of(
                debit("ac_tenant", "100.00"), credit("ac_ext", "100.00")));

        assertThatThrownBy(() -> service.post(tx))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo("VA_CHAIN_TAMPERED"));

        verify(entryRepo, never()).insert(any());
    }
}
