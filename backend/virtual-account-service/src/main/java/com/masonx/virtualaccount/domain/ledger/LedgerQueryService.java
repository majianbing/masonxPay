package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.error.BusinessException;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.po.LedgerEntry;
import com.masonx.virtualaccount.domain.po.VaAccount;
import com.masonx.virtualaccount.ledger.dto.AccountStatementResponse;
import com.masonx.virtualaccount.ledger.dto.LedgerEntryResponse;
import com.masonx.virtualaccount.ledger.dto.TransactionDetailResponse;
import com.masonx.virtualaccount.ledger.dto.TrialBalanceResponse;
import com.masonx.virtualaccount.ledger.dto.TrialBalanceResponse.TrialBalanceRow;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
public class LedgerQueryService {

    private final AccountRepository    accountRepo;
    private final LedgerEntryRepository entryRepo;
    private final TransactionRepository txRepo;

    public LedgerQueryService(AccountRepository accountRepo,
                              LedgerEntryRepository entryRepo,
                              TransactionRepository txRepo) {
        this.accountRepo = accountRepo;
        this.entryRepo   = entryRepo;
        this.txRepo      = txRepo;
    }

    public PagedResult<LedgerEntryResponse> listEntries(
            String accountId, String merchantId, Mode mode, int page, int size) {
        assertOwnership(accountId, merchantId, mode);
        int cap = Math.min(size, 100);
        List<LedgerEntryResponse> content = entryRepo.findByAccountId(accountId, page, cap)
                .stream().map(LedgerEntryResponse::from).toList();
        long total = entryRepo.countByAccountId(accountId);
        int totalPages = total == 0 ? 1 : (int) Math.ceil((double) total / cap);
        return new PagedResult<>(content, page, cap, total, totalPages);
    }

    public TransactionDetailResponse getTransactionDetail(
            String transactionId, String merchantId, Mode mode) {
        TransactionRecord tx = txRepo.findById(transactionId)
                .orElseThrow(() -> new BusinessException(
                        "VA_NOT_FOUND", "Transaction not found: " + transactionId));

        if (!merchantId.equals(tx.merchantId()) || mode != tx.mode()) {
            throw new BusinessException("VA_ACCESS_DENIED",
                    "Transaction " + transactionId + " not accessible for merchant=" + merchantId
                    + " mode=" + mode);
        }

        List<LedgerEntry> entries = entryRepo.findByTransactionId(transactionId);
        List<LedgerEntryResponse> entryResponses = entries.stream()
                .map(LedgerEntryResponse::from).toList();

        return new TransactionDetailResponse(
                tx.transactionId(),
                tx.entryType().name(),
                tx.description(),
                tx.paymentReferenceId(),
                tx.effectiveDate(),
                tx.status(),
                tx.mode().name(),
                tx.merchantId(),
                tx.createdAt(),
                entryResponses);
    }

    public AccountStatementResponse getStatement(
            String accountId, String merchantId, Mode mode, LocalDate from, LocalDate to) {
        VaAccount account = assertOwnership(accountId, merchantId, mode);

        BigDecimal openDebitNet  = entryRepo.sumDebitNetBeforeDate(accountId, from);
        BigDecimal closeDebitNet = entryRepo.sumDebitNetUpToDate(accountId, to);
        BigDecimal opening = toBalance(openDebitNet, account.normalBalance());
        BigDecimal closing = toBalance(closeDebitNet, account.normalBalance());

        List<LedgerEntry> periodEntries = entryRepo.findByAccountIdAndEffectiveDateRange(
                accountId, from, to);
        BigDecimal totalDebits  = periodEntries.stream()
                .filter(e -> e.direction() == Direction.DEBIT)
                .map(LedgerEntry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredits = periodEntries.stream()
                .filter(e -> e.direction() == Direction.CREDIT)
                .map(LedgerEntry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<LedgerEntryResponse> entryResponses = periodEntries.stream()
                .map(LedgerEntryResponse::from).toList();

        return new AccountStatementResponse(
                accountId,
                account.asset(),
                account.normalBalance().name(),
                from, to,
                opening, closing,
                totalDebits, totalCredits,
                closing.subtract(opening),
                entryResponses);
    }

    public TrialBalanceResponse getTrialBalance(Mode mode, String asset) {
        List<VaAccount> accounts = accountRepo.findAllByModeAndAsset(mode, asset);

        BigDecimal debitSide  = BigDecimal.ZERO;
        BigDecimal creditSide = BigDecimal.ZERO;

        List<TrialBalanceRow> rows = accounts.stream().map(a -> {
            return new TrialBalanceRow(
                    a.accountId(),
                    a.accountType().name(),
                    a.accountRole().name(),
                    a.merchantId(),
                    a.normalBalance().name(),
                    a.balance(),
                    a.frozenBalance());
        }).toList();

        for (VaAccount a : accounts) {
            if (a.normalBalance() == NormalBalance.DEBIT) {
                debitSide  = debitSide.add(a.balance());
            } else {
                creditSide = creditSide.add(a.balance());
            }
        }

        return new TrialBalanceResponse(
                mode.name(), asset, Instant.now(),
                debitSide, creditSide,
                debitSide.compareTo(creditSide) == 0,
                rows);
    }

    /**
     * Converts a debit-net value to a signed balance respecting the account's normal balance.
     * DEBIT-normal: balance increases with debits → balance = debitNet
     * CREDIT-normal: balance increases with credits → balance = −debitNet
     */
    BigDecimal toBalance(BigDecimal debitNet, NormalBalance normalBalance) {
        return normalBalance == NormalBalance.DEBIT ? debitNet : debitNet.negate();
    }

    /**
     * Validates that the account belongs to the given merchant AND is in the given mode.
     * Prevents TEST/LIVE data leakage and cross-merchant access.
     */
    VaAccount assertOwnership(String accountId, String merchantId, Mode mode) {
        VaAccount account = accountRepo.findById(accountId)
                .orElseThrow(() -> new BusinessException(
                        "VA_NOT_FOUND", "Account not found: " + accountId));
        if (!merchantId.equals(account.merchantId()) || mode != account.mode()) {
            throw new BusinessException("VA_ACCESS_DENIED",
                    "Account " + accountId + " not accessible for merchant=" + merchantId
                    + " mode=" + mode);
        }
        return account;
    }

}
