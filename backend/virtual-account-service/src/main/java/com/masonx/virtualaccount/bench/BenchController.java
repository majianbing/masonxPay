package com.masonx.virtualaccount.bench;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.ledger.*;

import java.time.LocalDate;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Internal bench endpoints — active ONLY when va.bench.enabled=true.
 * Never set this flag in a production deployment.
 *
 * POST /internal/bench/setup              → create N (tenant+external) ledger account pairs
 * POST /internal/bench/post              → one 2-entry balanced posting (DEBIT cash / CREDIT ext)
 * POST /internal/bench/verify-duplicate  → proves inbox idempotency with one duplicate event
 * GET  /internal/bench/verify/{ledgerAccountId} → balance + seq + HMAC + LC journal checks
 */
@RestController
@RequestMapping("/internal/bench")
@ConditionalOnProperty("va.bench.enabled")
public class BenchController {

    private final LedgerAccountRepository       accountRepo;
    private final LedgerFacade            ledger;
    private final BalanceSignatureService signatureService;
    private final SnowflakeIdGenerator    idGenerator;
    private final JdbcTemplate            jdbc;

    public BenchController(LedgerAccountRepository accountRepo,
                           LedgerFacade ledger,
                           BalanceSignatureService signatureService,
                           SnowflakeIdGenerator idGenerator,
                           JdbcTemplate jdbc) {
        this.accountRepo      = accountRepo;
        this.ledger           = ledger;
        this.signatureService = signatureService;
        this.idGenerator      = idGenerator;
        this.jdbc             = jdbc;
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    @PostMapping("/setup")
    public SetupResponse setup(@RequestBody SetupRequest req) {
        int n = req.pairCount() <= 0 ? 10 : req.pairCount();
        // Timestamp prefix makes IDs unique across setup calls in the same DB.
        String runId = "r" + System.currentTimeMillis();
        List<PairInfo> pairs = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            String cashId = "bch_" + runId + "_" + i;
            String extId  = "bex_" + runId + "_" + i;
            String mId    = "m_" + runId + "_" + i;
            String pId    = "p_" + runId + "_" + i;

            accountRepo.save(new LedgerAccount(cashId, Mode.TEST, LedgerAccountRole.TENANT,
                    "org_bench", mId, null, LedgerAccountType.CASH,
                    "USD", AssetClass.FIAT, 2,
                    NormalBalance.DEBIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE));

            accountRepo.save(new LedgerAccount(extId, Mode.TEST, LedgerAccountRole.EXTERNAL,
                    null, null, pId, LedgerAccountType.CLEARING,
                    "USD", AssetClass.FIAT, 2,
                    NormalBalance.CREDIT, BigDecimal.ZERO, LedgerAccountStatus.ACTIVE));

            pairs.add(new PairInfo(i, cashId, extId));
        }
        return new SetupResponse(runId, pairs);
    }

    // ── Post ──────────────────────────────────────────────────────────────────

    @PostMapping("/post")
    public PostResponse post(@RequestBody PostRequest req) {
        String txId = idGenerator.generate(MasonXIdPrefix.LEDGER_TRANSACTION.prefix());
        LedgerAccount tenantAccount = accountRepo.findById(req.tenantLedgerAccountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Ledger account not found: " + req.tenantLedgerAccountId()));
        ledger.postDirect(new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(req.tenantLedgerAccountId(), Direction.DEBIT,
                        req.amount(), "USD", "bench_" + txId),
                new AccountingEntryDraft(req.externalLedgerAccountId(), Direction.CREDIT,
                        req.amount(), "USD", "bench_" + txId)
        ), TransactionType.INTERNAL, "VA bench posting", null, LocalDate.now(),
                tenantAccount.mode(), tenantAccount.orgId(), tenantAccount.merchantId()));
        return new PostResponse(true);
    }

    @PostMapping("/verify-duplicate")
    public DuplicateVerifyResponse verifyDuplicate(@RequestBody PostRequest req) {
        String eventId = "bench_dup_" + idGenerator.generate("");
        String txId1 = idGenerator.generate(MasonXIdPrefix.LEDGER_TRANSACTION.prefix());
        String txId2 = idGenerator.generate(MasonXIdPrefix.LEDGER_TRANSACTION.prefix());
        LedgerAccount tenantAccount = accountRepo.findById(req.tenantLedgerAccountId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Ledger account not found: " + req.tenantLedgerAccountId()));

        LedgerPostingCommand first = duplicateCheckTransaction(req, tenantAccount, txId1, eventId);
        LedgerPostingCommand duplicate = duplicateCheckTransaction(req, tenantAccount, txId2, eventId);

        boolean firstPosted = ledger.postIfNew(first, eventId, "BENCH_DUPLICATE_CHECK");
        boolean duplicatePosted = ledger.postIfNew(duplicate, eventId, "BENCH_DUPLICATE_CHECK");

        Integer entryCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM va_ledger_entry WHERE source_event_id = ?",
                Integer.class, eventId);
        Integer headerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM va_transaction WHERE transaction_id IN (?, ?)",
                Integer.class, txId1, txId2);

        boolean ok = firstPosted && !duplicatePosted
                && Objects.equals(entryCount, 2)
                && Objects.equals(headerCount, 1);
        return new DuplicateVerifyResponse(ok, firstPosted, duplicatePosted,
                entryCount == null ? 0 : entryCount,
                headerCount == null ? 0 : headerCount);
    }

    // ── Verify ────────────────────────────────────────────────────────────────

    @GetMapping("/verify/{ledgerAccountId}")
    public VerifyResponse verify(@PathVariable String ledgerAccountId) {
        LedgerAccount account = accountRepo.findById(ledgerAccountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + ledgerAccountId));

        record EntryRow(long entrySeq, BigDecimal amount, Direction direction,
                        BigDecimal balanceAfter,
                        String prevSignature, String balanceSignature, String transactionId) {}

        List<EntryRow> entries = jdbc.query("""
                SELECT entry_seq, amount, direction, balance_after,
                       prev_signature, balance_signature, transaction_id
                FROM va_ledger_entry
                WHERE ledger_account_id = ?
                ORDER BY entry_seq ASC
                """,
                (rs, __) -> new EntryRow(
                        rs.getLong("entry_seq"),
                        rs.getBigDecimal("amount"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("balance_after"),
                        rs.getString("prev_signature"),
                        rs.getString("balance_signature"),
                        rs.getString("transaction_id")),
                ledgerAccountId);

        int entryCount = entries.size();
        BigDecimal accountBalance = account.balance();

        // Balance: last entry's balance_after must equal the account's current balance.
        boolean balanceOk = entryCount == 0
                ? accountBalance.compareTo(BigDecimal.ZERO) == 0
                : entries.get(entryCount - 1).balanceAfter().compareTo(accountBalance) == 0;

        BigDecimal debitNet = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0)
                FROM va_ledger_entry
                WHERE ledger_account_id = ?
                """, BigDecimal.class, ledgerAccountId);
        BigDecimal expectedBalance = account.normalBalance() == NormalBalance.DEBIT
                ? debitNet
                : debitNet.negate();
        boolean balanceSumOk = expectedBalance.compareTo(accountBalance) == 0;

        // Seq: must be 1, 2, 3, … N with no gaps or duplicates.
        boolean seqOk = true;
        Long firstGapAtSeq = null;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).entrySeq() != i + 1) {
                seqOk = false;
                firstGapAtSeq = entries.get(i).entrySeq();
                break;
            }
        }

        // HMAC chain: each entry stores its own balance_after and prev_signature,
        // so it is fully self-verifiable without external context.
        boolean chainOk = true;
        Long firstBrokenChainAtSeq = null;
        for (EntryRow e : entries) {
            String expected = signatureService.compute(new SignatureInput(
                    ledgerAccountId, e.entrySeq(), e.amount(), e.direction(),
                    e.balanceAfter(), e.transactionId(), e.prevSignature()));
            if (!expected.equals(e.balanceSignature())) {
                chainOk = false;
                firstBrokenChainAtSeq = e.entrySeq();
                break;
            }
        }

        Integer missingHeaderCount = jdbc.queryForObject("""
                WITH account_tx AS (
                    SELECT DISTINCT transaction_id
                    FROM va_ledger_entry
                    WHERE ledger_account_id = ?
                )
                SELECT COUNT(*)
                FROM account_tx atx
                LEFT JOIN va_transaction tx ON tx.transaction_id = atx.transaction_id
                WHERE tx.transaction_id IS NULL
                """, Integer.class, ledgerAccountId);

        Integer unbalancedTransactionCount = jdbc.queryForObject("""
                WITH account_tx AS (
                    SELECT DISTINCT transaction_id
                    FROM va_ledger_entry
                    WHERE ledger_account_id = ?
                )
                SELECT COUNT(*)
                FROM (
                    SELECT le.transaction_id
                    FROM va_ledger_entry le
                    JOIN account_tx atx ON atx.transaction_id = le.transaction_id
                    GROUP BY le.transaction_id
                    HAVING COALESCE(SUM(CASE WHEN le.direction = 'DEBIT' THEN le.amount ELSE -le.amount END), 0) <> 0
                ) broken
                """, Integer.class, ledgerAccountId);

        int headerScopeMismatchCount = 0;
        if (account.ledgerAccountRole() == LedgerAccountRole.TENANT) {
            Integer n = jdbc.queryForObject("""
                    WITH account_tx AS (
                        SELECT DISTINCT transaction_id
                        FROM va_ledger_entry
                        WHERE ledger_account_id = ?
                    )
                    SELECT COUNT(*)
                    FROM va_transaction tx
                    JOIN account_tx atx ON atx.transaction_id = tx.transaction_id
                    WHERE tx.mode <> ?::va_mode
                       OR tx.merchant_id IS DISTINCT FROM ?
                       OR tx.org_id IS DISTINCT FROM ?
                    """, Integer.class,
                    ledgerAccountId, account.mode().name(), account.merchantId(), account.orgId());
            headerScopeMismatchCount = n == null ? 0 : n;
        }

        boolean journalOk = (missingHeaderCount == null || missingHeaderCount == 0)
                && (unbalancedTransactionCount == null || unbalancedTransactionCount == 0)
                && headerScopeMismatchCount == 0;

        Boolean trialBalanceOk = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN normal_balance = 'DEBIT' THEN balance ELSE 0 END), 0)
                     = COALESCE(SUM(CASE WHEN normal_balance = 'CREDIT' THEN balance ELSE 0 END), 0)
                FROM ledger_account
                WHERE mode = ?::va_mode AND asset = ?
                """, Boolean.class, account.mode().name(), account.asset());

        return new VerifyResponse(ledgerAccountId, entryCount, accountBalance,
                balanceOk, balanceSumOk, seqOk, chainOk, journalOk,
                missingHeaderCount == null ? 0 : missingHeaderCount,
                unbalancedTransactionCount == null ? 0 : unbalancedTransactionCount,
                headerScopeMismatchCount,
                Boolean.TRUE.equals(trialBalanceOk),
                firstGapAtSeq, firstBrokenChainAtSeq);
    }

    private LedgerPostingCommand duplicateCheckTransaction(PostRequest req, LedgerAccount tenantAccount,
                                                      String txId, String eventId) {
        return new LedgerPostingCommand(txId, List.of(
                new AccountingEntryDraft(req.tenantLedgerAccountId(), Direction.DEBIT,
                        req.amount(), "USD", eventId),
                new AccountingEntryDraft(req.externalLedgerAccountId(), Direction.CREDIT,
                        req.amount(), "USD", eventId)
        ), TransactionType.INTERNAL, "VA bench duplicate check", null,
                LocalDate.now(), tenantAccount.mode(), tenantAccount.orgId(), tenantAccount.merchantId());
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record SetupRequest(int pairCount) {}
    public record SetupResponse(String runId, List<PairInfo> pairs) {}
    public record PairInfo(int index, String tenantLedgerAccountId, String externalLedgerAccountId) {}
    public record PostRequest(String tenantLedgerAccountId, String externalLedgerAccountId, BigDecimal amount) {}
    public record PostResponse(boolean ok) {}
    public record DuplicateVerifyResponse(
            boolean ok,
            boolean firstPosted,
            boolean duplicatePosted,
            int entryCount,
            int headerCount) {}
    public record VerifyResponse(
            String ledgerAccountId,
            int entryCount,
            BigDecimal accountBalance,
            boolean balanceOk,
            boolean balanceSumOk,
            boolean seqOk,
            boolean chainOk,
            boolean journalOk,
            int missingHeaderCount,
            int unbalancedTransactionCount,
            int headerScopeMismatchCount,
            boolean trialBalanceOk,
            Long firstGapAtSeq,
            Long firstBrokenChainAtSeq) {}
}
