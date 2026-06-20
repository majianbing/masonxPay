package com.masonx.virtualaccount.bench;

import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.*;
import com.masonx.virtualaccount.domain.ledger.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * Internal bench endpoints — active ONLY when va.bench.enabled=true.
 * Never set this flag in a production deployment.
 *
 * POST /internal/bench/setup              → create N (tenant+external) account pairs
 * POST /internal/bench/post              → one 2-entry balanced posting (DEBIT cash / CREDIT ext)
 * GET  /internal/bench/verify/{accountId} → balance + seq-gapless + HMAC chain check
 */
@RestController
@RequestMapping("/internal/bench")
@ConditionalOnProperty("va.bench.enabled")
public class BenchController {

    private final AccountRepository       accountRepo;
    private final LedgerPostingService    postingService;
    private final BalanceSignatureService signatureService;
    private final SnowflakeIdGenerator    idGenerator;
    private final JdbcTemplate            jdbc;

    public BenchController(AccountRepository accountRepo,
                           LedgerPostingService postingService,
                           BalanceSignatureService signatureService,
                           SnowflakeIdGenerator idGenerator,
                           JdbcTemplate jdbc) {
        this.accountRepo      = accountRepo;
        this.postingService   = postingService;
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

            accountRepo.save(new VaAccount(cashId, Mode.LIVE, AccountRole.TENANT,
                    "org_bench", mId, null, AccountType.CASH,
                    "USD", AssetClass.FIAT, 2,
                    NormalBalance.DEBIT, BigDecimal.ZERO, BigDecimal.ZERO, AccountStatus.ACTIVE));

            accountRepo.save(new VaAccount(extId, Mode.LIVE, AccountRole.EXTERNAL,
                    null, null, pId, AccountType.CLEARING,
                    "USD", AssetClass.FIAT, 2,
                    NormalBalance.CREDIT, BigDecimal.ZERO, BigDecimal.ZERO, AccountStatus.ACTIVE));

            pairs.add(new PairInfo(i, cashId, extId));
        }
        return new SetupResponse(runId, pairs);
    }

    // ── Post ──────────────────────────────────────────────────────────────────

    @PostMapping("/post")
    public PostResponse post(@RequestBody PostRequest req) {
        String txId = idGenerator.generate("tx_");
        postingService.post(new PostTransaction(txId, List.of(
                new EntryDraft(req.tenantAccountId(), Direction.DEBIT,
                        req.amount(), "USD", "bench_" + txId),
                new EntryDraft(req.externalAccountId(), Direction.CREDIT,
                        req.amount(), "USD", "bench_" + txId)
        )));
        return new PostResponse(true);
    }

    // ── Verify ────────────────────────────────────────────────────────────────

    @GetMapping("/verify/{accountId}")
    public VerifyResponse verify(@PathVariable String accountId) {
        VaAccount account = accountRepo.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

        record EntryRow(long entrySeq, BigDecimal amount, Direction direction,
                        BigDecimal balanceAfter, String balanceSignature, String transactionId) {}

        List<EntryRow> entries = jdbc.query("""
                SELECT entry_seq, amount, direction, balance_after,
                       balance_signature, transaction_id
                FROM va_ledger_entry
                WHERE account_id = ?
                ORDER BY entry_seq ASC
                """,
                (rs, __) -> new EntryRow(
                        rs.getLong("entry_seq"),
                        rs.getBigDecimal("amount"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("balance_after"),
                        rs.getString("balance_signature"),
                        rs.getString("transaction_id")),
                accountId);

        int entryCount = entries.size();
        BigDecimal accountBalance = account.balance();

        // Balance: last entry's balance_after must equal the account's current balance.
        boolean balanceOk = entryCount == 0
                ? accountBalance.compareTo(BigDecimal.ZERO) == 0
                : entries.get(entryCount - 1).balanceAfter().compareTo(accountBalance) == 0;

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

        // HMAC chain: recompute each entry's signature using prevSig and compare.
        // Bench accounts always have frozenBalance=0 (never frozen), so ZERO is correct here.
        boolean chainOk = true;
        Long firstBrokenChainAtSeq = null;
        String prevSig = ChainAnchor.GENESIS_SIGNATURE;
        for (EntryRow e : entries) {
            String expected = signatureService.compute(new SignatureInput(
                    accountId, e.entrySeq(), e.amount(), e.direction(),
                    e.balanceAfter(), BigDecimal.ZERO, e.transactionId(), prevSig));
            if (!expected.equals(e.balanceSignature())) {
                chainOk = false;
                firstBrokenChainAtSeq = e.entrySeq();
                break;
            }
            prevSig = e.balanceSignature();
        }

        return new VerifyResponse(accountId, entryCount, accountBalance,
                balanceOk, seqOk, chainOk, firstGapAtSeq, firstBrokenChainAtSeq);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record SetupRequest(int pairCount) {}
    public record SetupResponse(String runId, List<PairInfo> pairs) {}
    public record PairInfo(int index, String tenantAccountId, String externalAccountId) {}
    public record PostRequest(String tenantAccountId, String externalAccountId, BigDecimal amount) {}
    public record PostResponse(boolean ok) {}
    public record VerifyResponse(
            String accountId,
            int entryCount,
            BigDecimal accountBalance,
            boolean balanceOk,
            boolean seqOk,
            boolean chainOk,
            Long firstGapAtSeq,
            Long firstBrokenChainAtSeq) {}
}
