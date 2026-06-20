package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
    @Mock BalanceSignatureService  signatureService;

    LedgerPostingService service;

    @BeforeEach
    void setUp() {
        service = new LedgerPostingService(
                accountRepo, entryRepo, signatureService,
                new SnowflakeIdGenerator(0));
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

    // --- validation tests ---

    @Test
    void rejects_unbalanced_transaction() {
        var tx = new PostTransaction("tx_1", List.of(
                credit("ac_tenant", "100.00"),
                debit("ac_external", "90.00")));  // 100 != 90

        assertThatThrownBy(() -> service.post(tx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("balanced");
    }

    @Test
    void rejects_mixed_asset_transaction() {
        var tx = new PostTransaction("tx_1", List.of(
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

        var tx = new PostTransaction("tx_1", List.of(
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
        // Exception is thrown in computeBalance before the signature/anchor calls are ever reached.
        var tx = new PostTransaction("tx_1", List.of(
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
        when(entryRepo.findLastAnchor(any())).thenReturn(Optional.empty());
        when(signatureService.compute(any())).thenReturn("sig_abc");

        // Settlement: provider clears $100 to merchant.
        // CASH is DEBIT-normal — money in = DEBIT merchant, CREDIT external.
        var tx = new PostTransaction("tx_1", List.of(
                debit("ac_tenant",  "100.00"),
                credit("ac_ext",    "100.00")));

        service.post(tx);

        verify(entryRepo, times(2)).insert(any());
        verify(accountRepo, times(2)).updateBalance(any(), any(), any());
    }

    @Test
    void entry_seq_starts_at_1_when_no_prior_entries() {
        when(accountRepo.findByIdForUpdate("ac_tenant")).thenReturn(
                Optional.of(cashAccount("ac_tenant", BigDecimal.ZERO)));
        when(accountRepo.findByIdForUpdate("ac_ext")).thenReturn(
                Optional.of(externalAccount("ac_ext")));
        when(entryRepo.findLastAnchor(any())).thenReturn(Optional.empty());
        when(signatureService.compute(any())).thenReturn("sig");

        service.post(new PostTransaction("tx_1", List.of(
                debit("ac_tenant", "100.00"), credit("ac_ext", "100.00"))));

        var captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepo, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).allMatch(e -> e.entrySeq() == 1L);
    }

    @Test
    void uses_genesis_signature_for_first_entry() {
        when(accountRepo.findByIdForUpdate(any())).thenReturn(
                Optional.of(cashAccount("ac_any", BigDecimal.ZERO)));
        when(entryRepo.findLastAnchor(any())).thenReturn(Optional.empty());
        when(signatureService.compute(any())).thenReturn("sig");

        // CASH is DEBIT-normal: debit first (increases balance), then credit back to zero.
        service.post(new PostTransaction("tx_1", List.of(
                debit("ac_any", "100.00"), credit("ac_any", "100.00"))));

        var sigCaptor = ArgumentCaptor.forClass(SignatureInput.class);
        verify(signatureService, atLeastOnce()).compute(sigCaptor.capture());
        assertThat(sigCaptor.getAllValues())
                .allMatch(s -> s.prevSignature().equals(ChainAnchor.GENESIS_SIGNATURE));
    }

    @Test
    void chains_prev_signature_from_last_entry() {
        String prevSig = "prev-sig-abc";
        when(accountRepo.findByIdForUpdate(any())).thenReturn(
                Optional.of(cashAccount("ac_any", new BigDecimal("200.00"))));
        when(entryRepo.findLastAnchor(any()))
                .thenReturn(Optional.of(new ChainAnchor(5L, prevSig)));
        when(signatureService.compute(any())).thenReturn("new-sig");

        service.post(new PostTransaction("tx_1", List.of(
                debit("ac_any", "50.00"), credit("ac_any", "50.00"))));

        var captor = ArgumentCaptor.forClass(SignatureInput.class);
        verify(signatureService, atLeastOnce()).compute(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(s -> s.prevSignature().equals(prevSig));
    }
}
