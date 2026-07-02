package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.error.BusinessException;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.ledger.dto.AccountStatementResponse;
import com.masonx.virtualaccount.ledger.dto.LedgerEntryResponse;
import com.masonx.virtualaccount.ledger.dto.TransactionDetailResponse;
import com.masonx.virtualaccount.ledger.dto.TrialBalanceResponse;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerQueryServiceTest {

    @Mock AccountRepository     accountRepo;
    @Mock LedgerEntryRepository entryRepo;
    @Mock TransactionRepository txRepo;

    LedgerQueryService service;

    @BeforeEach
    void setUp() {
        service = new LedgerQueryService(accountRepo, entryRepo, txRepo);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private VaAccount tenantAccount(String id, String merchantId, Mode mode) {
        return new VaAccount(id, mode, AccountRole.TENANT,
                "org_1", merchantId, null, AccountType.CASH,
                "USD", AssetClass.FIAT, 2, NormalBalance.DEBIT,
                BigDecimal.ZERO, AccountStatus.ACTIVE);
    }

    private TransactionRecord txRecord(String txId, String merchantId, Mode mode) {
        return new TransactionRecord(txId, TransactionType.SETTLEMENT, "Settlement ref_1",
                "pi_abc", LocalDate.of(2026, 1, 15), "POSTED",
                mode, "org_1", merchantId, Instant.now());
    }

    private com.masonx.virtualaccount.domain.po.LedgerEntry entry(String entryId, String accountId, long seq) {
        return new com.masonx.virtualaccount.domain.po.LedgerEntry(
                entryId, "tx_1", accountId,
                com.masonx.virtualaccount.domain.constant.Direction.DEBIT,
                new BigDecimal("100.00"), "USD", seq,
                new BigDecimal("100.00"),
                "GENESIS", "sig_abc", "evt_1",
                EntryStatus.POSTED, LocalDate.of(2026, 1, 15), Instant.now());
    }

    // ── assertOwnership ───────────────────────────────────────────────────────

    @Test
    void assertOwnership_passes_when_merchantId_and_mode_match() {
        when(accountRepo.findById("ac_1")).thenReturn(Optional.of(tenantAccount("ac_1", "mer_1", Mode.LIVE)));

        VaAccount result = service.assertOwnership("ac_1", "mer_1", Mode.LIVE);

        assertThat(result.accountId()).isEqualTo("ac_1");
    }

    @Test
    void assertOwnership_rejects_wrong_merchantId() {
        when(accountRepo.findById("ac_1")).thenReturn(Optional.of(tenantAccount("ac_1", "mer_1", Mode.LIVE)));

        assertThatThrownBy(() -> service.assertOwnership("ac_1", "mer_WRONG", Mode.LIVE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo("VA_ACCESS_DENIED"));
    }

    @Test
    void assertOwnership_rejects_wrong_mode() {
        when(accountRepo.findById("ac_1")).thenReturn(Optional.of(tenantAccount("ac_1", "mer_1", Mode.LIVE)));

        assertThatThrownBy(() -> service.assertOwnership("ac_1", "mer_1", Mode.TEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo("VA_ACCESS_DENIED"));
    }

    @Test
    void assertOwnership_rejects_unknown_account() {
        when(accountRepo.findById("ac_ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertOwnership("ac_ghost", "mer_1", Mode.LIVE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo("VA_NOT_FOUND"));
    }

    // ── listEntries ───────────────────────────────────────────────────────────

    @Test
    void listEntries_returns_paged_result_with_correct_total() {
        when(accountRepo.findById("ac_1")).thenReturn(Optional.of(tenantAccount("ac_1", "mer_1", Mode.LIVE)));
        when(entryRepo.findByAccountId("ac_1", 0, 20)).thenReturn(List.of(entry("le_1", "ac_1", 1L)));
        when(entryRepo.countByAccountId("ac_1")).thenReturn(1L);

        PagedResult<LedgerEntryResponse> result = service.listEntries("ac_1", "mer_1", Mode.LIVE, 0, 20);

        assertThat(result.content()).hasSize(1);
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.content().get(0).entryId()).isEqualTo("le_1");
    }

    @Test
    void listEntries_caps_page_size_at_100() {
        when(accountRepo.findById("ac_1")).thenReturn(Optional.of(tenantAccount("ac_1", "mer_1", Mode.LIVE)));
        when(entryRepo.findByAccountId("ac_1", 0, 100)).thenReturn(List.of());
        when(entryRepo.countByAccountId("ac_1")).thenReturn(0L);

        PagedResult<LedgerEntryResponse> result = service.listEntries("ac_1", "mer_1", Mode.LIVE, 0, 500);

        assertThat(result.size()).isEqualTo(100);
        verify(entryRepo).findByAccountId("ac_1", 0, 100);
    }

    // ── getTransactionDetail ──────────────────────────────────────────────────

    @Test
    void getTransactionDetail_returns_tx_with_entries() {
        when(txRepo.findById("tx_1")).thenReturn(Optional.of(txRecord("tx_1", "mer_1", Mode.LIVE)));
        when(entryRepo.findByTransactionId("tx_1")).thenReturn(List.of(entry("le_1", "ac_1", 1L)));

        TransactionDetailResponse detail = service.getTransactionDetail("tx_1", "mer_1", Mode.LIVE);

        assertThat(detail.transactionId()).isEqualTo("tx_1");
        assertThat(detail.entryType()).isEqualTo("SETTLEMENT");
        assertThat(detail.entries()).hasSize(1);
    }

    @Test
    void getTransactionDetail_rejects_wrong_merchantId() {
        when(txRepo.findById("tx_1")).thenReturn(Optional.of(txRecord("tx_1", "mer_1", Mode.LIVE)));

        assertThatThrownBy(() -> service.getTransactionDetail("tx_1", "mer_WRONG", Mode.LIVE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo("VA_ACCESS_DENIED"));

        verifyNoInteractions(entryRepo);
    }

    @Test
    void getTransactionDetail_rejects_wrong_mode() {
        when(txRepo.findById("tx_1")).thenReturn(Optional.of(txRecord("tx_1", "mer_1", Mode.LIVE)));

        assertThatThrownBy(() -> service.getTransactionDetail("tx_1", "mer_1", Mode.TEST))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo("VA_ACCESS_DENIED"));

        verifyNoInteractions(entryRepo);
    }

    @Test
    void getTransactionDetail_returns_404_for_unknown_transaction() {
        when(txRepo.findById("tx_ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransactionDetail("tx_ghost", "mer_1", Mode.LIVE))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).code()).isEqualTo("VA_NOT_FOUND"));
    }

    // ── toBalance ─────────────────────────────────────────────────────────────

    @Test
    void toBalance_debit_normal_returns_debitNet_as_is() {
        BigDecimal debitNet = new BigDecimal("150.00");
        assertThat(service.toBalance(debitNet, NormalBalance.DEBIT))
                .isEqualByComparingTo("150.00");
    }

    @Test
    void toBalance_credit_normal_negates_debitNet() {
        // CREDIT-normal account: Σ(CREDIT) > Σ(DEBIT) → positive balance.
        // debitNet = Σ(DEBIT) − Σ(CREDIT) = −200 → balance = −(−200) = 200
        BigDecimal debitNet = new BigDecimal("-200.00");
        assertThat(service.toBalance(debitNet, NormalBalance.CREDIT))
                .isEqualByComparingTo("200.00");
    }

    // ── getStatement ──────────────────────────────────────────────────────────

    private VaAccount debitNormalAccount(String id, String merchantId) {
        return new VaAccount(id, Mode.LIVE, AccountRole.TENANT,
                "org_1", merchantId, null, AccountType.CASH,
                "USD", AssetClass.FIAT, 2, NormalBalance.DEBIT,
                new BigDecimal("300.00"), AccountStatus.ACTIVE);
    }

    private com.masonx.virtualaccount.domain.po.LedgerEntry debitEntry(
            String id, BigDecimal amount, LocalDate date) {
        return new com.masonx.virtualaccount.domain.po.LedgerEntry(
                id, "tx_1", "ac_1",
                com.masonx.virtualaccount.domain.constant.Direction.DEBIT,
                amount, "USD", 1L, amount,
                "GENESIS", "sig", "evt_1",
                EntryStatus.POSTED, date, Instant.now());
    }

    private com.masonx.virtualaccount.domain.po.LedgerEntry creditEntry(
            String id, BigDecimal amount, LocalDate date) {
        return new com.masonx.virtualaccount.domain.po.LedgerEntry(
                id, "tx_1", "ac_1",
                com.masonx.virtualaccount.domain.constant.Direction.CREDIT,
                amount, "USD", 2L, amount,
                "sig_prev", "sig", "evt_2",
                EntryStatus.POSTED, date, Instant.now());
    }

    @Test
    void statement_opening_is_zero_when_no_prior_entries() {
        when(accountRepo.findById("ac_1")).thenReturn(Optional.of(debitNormalAccount("ac_1", "mer_1")));
        when(entryRepo.sumDebitNetBeforeDate("ac_1", LocalDate.of(2026, 1, 1)))
                .thenReturn(BigDecimal.ZERO);
        when(entryRepo.sumDebitNetUpToDate("ac_1", LocalDate.of(2026, 1, 31)))
                .thenReturn(new BigDecimal("100.00"));
        when(entryRepo.findByAccountIdAndEffectiveDateRange(
                "ac_1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(List.of(debitEntry("le_1", new BigDecimal("100.00"), LocalDate.of(2026, 1, 15))));

        AccountStatementResponse stmt = service.getStatement(
                "ac_1", "mer_1", Mode.LIVE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(stmt.openingBalance()).isEqualByComparingTo("0.00");
        assertThat(stmt.closingBalance()).isEqualByComparingTo("100.00");
        assertThat(stmt.netChange()).isEqualByComparingTo("100.00");
        assertThat(stmt.totalDebits()).isEqualByComparingTo("100.00");
        assertThat(stmt.totalCredits()).isEqualByComparingTo("0.00");
        assertThat(stmt.entries()).hasSize(1);
    }

    @Test
    void statement_backdated_entry_appears_in_prior_period_opening_not_in_current_period_entries() {
        // Entry has effective_date = Jan-10 but was posted in March.
        // Feb statement: Jan-10 entry must be in opening balance, NOT in period entries.
        LocalDate jan10 = LocalDate.of(2026, 1, 10);
        LocalDate feb1  = LocalDate.of(2026, 2, 1);
        LocalDate feb28 = LocalDate.of(2026, 2, 28);

        when(accountRepo.findById("ac_1")).thenReturn(Optional.of(debitNormalAccount("ac_1", "mer_1")));
        // Opening: entries strictly before Feb 1 — includes Jan-10 entry
        when(entryRepo.sumDebitNetBeforeDate("ac_1", feb1))
                .thenReturn(new BigDecimal("100.00"));
        when(entryRepo.sumDebitNetUpToDate("ac_1", feb28))
                .thenReturn(new BigDecimal("100.00"));   // no new entries in Feb
        // Period entries for Feb: empty (Jan-10 entry falls outside BETWEEN feb1 AND feb28)
        when(entryRepo.findByAccountIdAndEffectiveDateRange("ac_1", feb1, feb28))
                .thenReturn(List.of());

        AccountStatementResponse stmt = service.getStatement(
                "ac_1", "mer_1", Mode.LIVE, feb1, feb28);

        assertThat(stmt.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(stmt.closingBalance()).isEqualByComparingTo("100.00");
        assertThat(stmt.netChange()).isEqualByComparingTo("0.00");
        assertThat(stmt.entries()).isEmpty();
    }

    // ── getTrialBalance ───────────────────────────────────────────────────────

    private VaAccount account(String id, AccountRole role, AccountType type,
                              NormalBalance nb, BigDecimal balance, String merchantId) {
        return new VaAccount(id, Mode.LIVE, role,
                "org_1", merchantId, role == AccountRole.EXTERNAL ? "prov_1" : null,
                type, "USD", AssetClass.FIAT, 2, nb,
                balance, AccountStatus.ACTIVE);
    }

    @Test
    void trial_balance_is_balanced_when_debit_side_equals_credit_side() {
        VaAccount cash    = account("ac_cash", AccountRole.TENANT,   AccountType.CASH,
                NormalBalance.DEBIT,  new BigDecimal("1000.00"), "mer_1");
        VaAccount clearing = account("ac_clr", AccountRole.EXTERNAL, AccountType.CLEARING,
                NormalBalance.CREDIT, new BigDecimal("1000.00"), null);

        when(accountRepo.findAllByModeAndAsset(Mode.LIVE, "USD"))
                .thenReturn(List.of(cash, clearing));

        TrialBalanceResponse tb = service.getTrialBalance(Mode.LIVE, "USD");

        assertThat(tb.balanced()).isTrue();
        assertThat(tb.totalDebitSideBalance()).isEqualByComparingTo("1000.00");
        assertThat(tb.totalCreditSideBalance()).isEqualByComparingTo("1000.00");
        assertThat(tb.rows()).hasSize(2);
    }

    @Test
    void trial_balance_is_not_balanced_when_sides_differ() {
        VaAccount cash    = account("ac_cash", AccountRole.TENANT,   AccountType.CASH,
                NormalBalance.DEBIT,  new BigDecimal("1000.00"), "mer_1");
        VaAccount clearing = account("ac_clr", AccountRole.EXTERNAL, AccountType.CLEARING,
                NormalBalance.CREDIT, new BigDecimal("900.00"), null);

        when(accountRepo.findAllByModeAndAsset(Mode.LIVE, "USD"))
                .thenReturn(List.of(cash, clearing));

        TrialBalanceResponse tb = service.getTrialBalance(Mode.LIVE, "USD");

        assertThat(tb.balanced()).isFalse();
    }

    @Test
    void trial_balance_is_balanced_with_no_accounts() {
        when(accountRepo.findAllByModeAndAsset(Mode.TEST, "USD")).thenReturn(List.of());

        TrialBalanceResponse tb = service.getTrialBalance(Mode.TEST, "USD");

        assertThat(tb.balanced()).isTrue();
        assertThat(tb.totalDebitSideBalance()).isEqualByComparingTo("0");
        assertThat(tb.totalCreditSideBalance()).isEqualByComparingTo("0");
        assertThat(tb.rows()).isEmpty();
    }

    @Test
    void trial_balance_rows_include_merchantId_for_tenant_accounts() {
        VaAccount cash = account("ac_cash", AccountRole.TENANT, AccountType.CASH,
                NormalBalance.DEBIT, new BigDecimal("500.00"), "mer_1");
        when(accountRepo.findAllByModeAndAsset(Mode.LIVE, "USD")).thenReturn(List.of(cash));

        TrialBalanceResponse tb = service.getTrialBalance(Mode.LIVE, "USD");

        assertThat(tb.rows().get(0).merchantId()).isEqualTo("mer_1");
        assertThat(tb.rows().get(0).normalBalance()).isEqualTo("DEBIT");
    }

    @Test
    void statement_credit_normal_account_sign_is_correct() {
        // CREDIT-normal TENANT account: credits increase balance.
        // Σ CREDIT 150, Σ DEBIT 0 → debitNet = 0 − 150 = −150 → balance = −(−150) = +150
        VaAccount creditAcct = new VaAccount("ac_crl", Mode.LIVE, AccountRole.TENANT,
                "org_1", "mer_1", null, AccountType.CREDIT_LINE,
                "USD", AssetClass.FIAT, 2, NormalBalance.CREDIT,
                BigDecimal.ZERO, AccountStatus.ACTIVE);
        when(accountRepo.findById("ac_crl")).thenReturn(Optional.of(creditAcct));
        when(entryRepo.sumDebitNetBeforeDate("ac_crl", LocalDate.of(2026, 1, 1)))
                .thenReturn(BigDecimal.ZERO);
        when(entryRepo.sumDebitNetUpToDate("ac_crl", LocalDate.of(2026, 1, 31)))
                .thenReturn(new BigDecimal("-150.00"));
        when(entryRepo.findByAccountIdAndEffectiveDateRange(
                "ac_crl", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(List.of(creditEntry("le_1", new BigDecimal("150.00"), LocalDate.of(2026, 1, 10))));

        AccountStatementResponse stmt = service.getStatement(
                "ac_crl", "mer_1", Mode.LIVE,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThat(stmt.normalBalance()).isEqualTo("CREDIT");
        assertThat(stmt.closingBalance()).isEqualByComparingTo("150.00");
        assertThat(stmt.totalCredits()).isEqualByComparingTo("150.00");
    }
}
