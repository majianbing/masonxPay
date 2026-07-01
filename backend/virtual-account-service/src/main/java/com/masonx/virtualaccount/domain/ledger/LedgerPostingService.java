package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.error.BusinessException;
import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.constant.AccountStatus;
import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.EntryStatus;
import com.masonx.virtualaccount.domain.constant.NormalBalance;
import com.masonx.virtualaccount.domain.ledger.validator.api.EntryValidator;
import com.masonx.virtualaccount.domain.ledger.validator.api.TransactionValidator;
import com.masonx.virtualaccount.domain.po.LedgerEntry;
import com.masonx.virtualaccount.domain.po.VaAccount;
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
 * <p>
 * Validation is structured as two ordered chains:
 * - TransactionValidator: stateless pre-lock checks on the full PostTransaction
 * - EntryValidator: per-entry checks with locked account context and computed balance
 * <p>
 * Adding a new check = new @Component implementing one of those interfaces; no
 * changes to this class required.
 * <p>
 * Lock ordering: accounts are locked by account_id (alphabetical) to prevent
 * deadlocks when concurrent transactions share overlapping accounts.
 * <p>
 * The HMAC chain guarantees tamper-evidence: entry_seq, balance_after, and
 * frozen_balance are all signed. Any direct DB edit is detectable at next post.
 */
@Service
public class LedgerPostingService {

    private final AccountRepository accountRepo;
    private final LedgerEntryRepository entryRepo;
    private final TransactionRepository txRepo;
    private final BalanceSignatureService signatureService;
    private final SnowflakeIdGenerator idGenerator;
    private final List<TransactionValidator> txValidators;
    private final List<EntryValidator> entryValidators;

    public LedgerPostingService(AccountRepository accountRepo,
                                LedgerEntryRepository entryRepo,
                                TransactionRepository txRepo,
                                BalanceSignatureService signatureService,
                                SnowflakeIdGenerator idGenerator,
                                List<TransactionValidator> txValidators,
                                List<EntryValidator> entryValidators) {
        this.accountRepo = accountRepo;
        this.entryRepo = entryRepo;
        this.txRepo = txRepo;
        this.signatureService = signatureService;
        this.idGenerator = idGenerator;
        this.txValidators = txValidators;
        this.entryValidators = entryValidators;
    }

    @Transactional
    void post(PostTransaction tx) {
        // Persist journal entry header first — same DB transaction as all entry inserts.
        txRepo.insert(tx);

        // Phase 1: transaction-level validation — stateless, pre-lock.
        for (TransactionValidator v : txValidators) {
            v.validate(tx);
        }

        // Lock accounts in sorted order — prevents deadlocks across concurrent transactions.
        List<String> sortedAccountIds = tx.entries().stream()
                .map(EntryDraft::accountId)
                .distinct()
                .sorted()
                .toList();

        Map<String, VaAccount> accounts = new HashMap<>();
        for (String accountId : sortedAccountIds) {
            VaAccount account = accountRepo.findByIdForUpdate(accountId)
                    .orElseThrow(() -> new BusinessException(
                            "VA_ACCOUNT_NOT_FOUND", "Account not found: " + accountId));
            // Status is checked here, not in the entry chain, so ALL accounts are validated
            // before any entry is written — a frozen account is caught before any DB mutation.
            if (account.status() != AccountStatus.ACTIVE) {
                throw new BusinessException(
                        "VA_ACCOUNT_NOT_ACTIVE", "Account is not active: " + accountId);
            }
            accounts.put(accountId, account);
        }

        // Phase 2: per-entry validation chain + posting.
        for (EntryDraft draft : tx.entries()) {
            VaAccount account = accounts.get(draft.accountId());
            BigDecimal newBalance = computeNewBalance(account, draft);

            for (EntryValidator v : entryValidators) {
                v.validate(draft, account, newBalance);
            }

            // Insert entry: verify chain head (DB read), then persist.
            ChainAnchor anchor = resolveAndVerifyHead(draft.accountId(), account);

            long entrySeq = anchor.entrySeq() + 1;

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
                    idGenerator.generate(MasonXIdPrefix.LEDGER_ENTRY.prefix()),
                    tx.transactionId(),
                    draft.accountId(),
                    draft.direction(),
                    draft.amount(),
                    draft.asset(),
                    entrySeq,
                    newBalance,
                    account.frozenBalance(),
                    anchor.signature(),
                    signature,
                    draft.sourceEventId(),
                    EntryStatus.POSTED,
                    tx.effectiveDate(),
                    Instant.now());

            entryRepo.insert(entry);
            accountRepo.updateBalance(draft.accountId(), newBalance, account.frozenBalance());

            // Reflect new balance in local map for any subsequent entries on this account.
            accounts.put(draft.accountId(), account.withBalance(newBalance));
        }
    }

    /**
     * Fetches the last entry for the account, runs two tamper checks, then
     * returns the anchor (seq + signature) to chain the new entry onto.
     * <p>
     * Check 1 — balance cross-check:
     * va_account.balance must equal the last entry's balance_after.
     * Catches a direct UPDATE on va_account that didn't touch va_ledger_entry.
     * Safe because the account row is already held under SELECT FOR UPDATE.
     * <p>
     * Check 2 — HMAC chain integrity:
     * Recomputes the last entry's signature from its stored fields.
     * Catches a direct UPDATE on va_ledger_entry (balance_after, amount, etc.).
     * Requires knowing the HMAC secret to evade.
     * <p>
     * Both checks must pass before any new entry is chained onto this account.
     */
    private ChainAnchor resolveAndVerifyHead(String accountId, VaAccount account) {
        return entryRepo.findLastChainHead(accountId)
                .map(head -> {
                    // Check 1: account balance must match the last posted entry.
                    if (head.balanceAfter().compareTo(account.balance()) != 0) {
                        throw new BusinessException("VA_BALANCE_MISMATCH",
                                "Account balance " + account.balance()
                                        + " does not match last entry balance_after " + head.balanceAfter()
                                        + " for account " + accountId + " at seq=" + head.entrySeq()
                                        + ". Direct va_account modification suspected.");
                    }

                    // Check 2: HMAC chain integrity on the last ledger entry.
                    boolean valid = signatureService.verify(
                            new SignatureInput(
                                    accountId,
                                    head.entrySeq(),
                                    head.amount(),
                                    head.direction(),
                                    head.balanceAfter(),
                                    head.frozenBalance(),
                                    head.transactionId(),
                                    head.prevSignature()),
                            head.signature());
                    if (!valid) {
                        throw new BusinessException("VA_CHAIN_TAMPERED",
                                "Ledger chain integrity check failed for account " + accountId
                                        + " at seq=" + head.entrySeq()
                                        + ". Direct va_ledger_entry modification suspected.");
                    }

                    return head.toAnchor();
                })
                .orElseGet(() -> {
                    // No entries yet — account balance must be zero.
                    if (account.balance().compareTo(BigDecimal.ZERO) != 0) {
                        throw new BusinessException("VA_BALANCE_MISMATCH",
                                "Account " + accountId
                                        + " has no ledger entries but balance is " + account.balance()
                                        + ". Direct va_account modification suspected.");
                    }
                    return new ChainAnchor(0L, ChainAnchor.GENESIS_SIGNATURE);
                });
    }

    /**
     * Pure arithmetic — applies direction against normal balance and returns the result.
     */
    private BigDecimal computeNewBalance(VaAccount account, EntryDraft draft) {
        boolean increases = account.normalBalance() == NormalBalance.DEBIT
                ? draft.direction() == Direction.DEBIT
                : draft.direction() == Direction.CREDIT;
        return increases
                ? account.balance().add(draft.amount())
                : account.balance().subtract(draft.amount());
    }
}
