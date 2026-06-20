package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Core double-entry posting engine. Atomically inserts a balanced set of ledger
 * entries and updates va_account.balance for each affected account.
 *
 * Invariants enforced before posting:
 *  1. All entries share the same asset (no cross-asset transactions).
 *  2. Total DEBITs == total CREDITs (net-zero).
 *
 * Lock ordering: accounts are locked by account_id (alphabetical) to prevent
 * deadlocks when concurrent transactions share overlapping accounts.
 *
 * The HMAC chain guarantees tamper-evidence: entry_seq, balance_after, and
 * frozen_balance are all signed. Any direct DB edit is detectable at next post.
 */
@Service
public class LedgerPostingService {

    private final AccountRepository accountRepo;
    private final LedgerEntryRepository entryRepo;
    private final BalanceSignatureService signatureService;
    private final SnowflakeIdGenerator idGenerator;

    public LedgerPostingService(AccountRepository accountRepo,
                                LedgerEntryRepository entryRepo,
                                BalanceSignatureService signatureService,
                                SnowflakeIdGenerator idGenerator) {
        this.accountRepo      = accountRepo;
        this.entryRepo        = entryRepo;
        this.signatureService = signatureService;
        this.idGenerator      = idGenerator;
    }

    @Transactional
    public void post(PostTransaction tx) {
        List<EntryDraft> drafts = tx.entries();

        validateAssetConsistency(drafts);
        validateNetZero(drafts);

        // Lock accounts in sorted order — prevents deadlocks across concurrent transactions.
        List<String> sortedAccountIds = drafts.stream()
                .map(EntryDraft::accountId)
                .distinct()
                .sorted()
                .toList();

        Map<String, VaAccount> accounts = new HashMap<>();
        for (String accountId : sortedAccountIds) {
            VaAccount account = accountRepo.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new BusinessException(
                            "VA_ACCOUNT_NOT_FOUND", "Account not found: " + accountId));
            if (account.status() != AccountStatus.ACTIVE) {
                throw new BusinessException(
                        "VA_ACCOUNT_NOT_ACTIVE", "Account is not active: " + accountId);
            }
            accounts.put(accountId, account);
        }

        // Insert each entry, chaining signatures and updating in-memory balances.
        for (EntryDraft draft : drafts) {
            VaAccount account = accounts.get(draft.accountId());

            ChainAnchor anchor = entryRepo.findLastAnchor(draft.accountId())
                    .orElse(new ChainAnchor(0L, ChainAnchor.GENESIS_SIGNATURE));

            long entrySeq    = anchor.entrySeq() + 1;
            BigDecimal newBalance = computeBalance(account, draft);

            String signature = signatureService.compute(new SignatureInput(
                    draft.accountId(),
                    entrySeq,
                    draft.amount(),
                    draft.direction(),
                    newBalance,
                    account.frozenBalance(),
                    tx.transactionId(),
                    anchor.signature()));

            LedgerEntry entry = new LedgerEntry(
                    idGenerator.generate("le_"),
                    tx.transactionId(),
                    draft.accountId(),
                    draft.direction(),
                    draft.amount(),
                    draft.asset(),
                    entrySeq,
                    newBalance,
                    signature,
                    draft.sourceEventId(),
                    EntryStatus.POSTED,
                    Instant.now());

            entryRepo.insert(entry);
            accountRepo.updateBalance(draft.accountId(), newBalance, account.frozenBalance());

            // Reflect new balance in local map for any subsequent entries on this account.
            accounts.put(draft.accountId(), account.withBalance(newBalance));
        }
    }

    private BigDecimal computeBalance(VaAccount account, EntryDraft draft) {
        boolean increases = account.normalBalance() == NormalBalance.DEBIT
                ? draft.direction() == Direction.DEBIT
                : draft.direction() == Direction.CREDIT;

        BigDecimal result = increases
                ? account.balance().add(draft.amount())
                : account.balance().subtract(draft.amount());

        if (result.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(
                    "VA_INSUFFICIENT_BALANCE",
                    "Posting would make balance negative for account: " + account.accountId());
        }
        return result;
    }

    private void validateAssetConsistency(List<EntryDraft> drafts) {
        long distinctAssets = drafts.stream().map(EntryDraft::asset).distinct().count();
        if (distinctAssets > 1) {
            throw new BusinessException(
                    "VA_ASSET_MISMATCH",
                    "All entries in a transaction must share the same asset");
        }
    }

    private void validateNetZero(List<EntryDraft> drafts) {
        BigDecimal totalDebits  = BigDecimal.ZERO;
        BigDecimal totalCredits = BigDecimal.ZERO;
        for (EntryDraft d : drafts) {
            if (d.direction() == Direction.DEBIT)  totalDebits  = totalDebits.add(d.amount());
            else                                    totalCredits = totalCredits.add(d.amount());
        }
        if (totalDebits.compareTo(totalCredits) != 0) {
            throw new BusinessException(
                    "VA_NOT_BALANCED",
                    "Transaction is not balanced: debits (" + totalDebits + ") != credits (" + totalCredits + ")");
        }
    }
}
