package com.masonx.virtualaccount.ledger;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.ledger.LedgerQueryService;
import com.masonx.virtualaccount.ledger.dto.AccountStatementResponse;
import com.masonx.virtualaccount.ledger.dto.LedgerEntryResponse;
import com.masonx.virtualaccount.ledger.dto.TransactionDetailResponse;
import com.masonx.virtualaccount.ledger.dto.TrialBalanceResponse;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * GL query endpoints — require X-Internal-Token (INTERNAL role).
 * All account-scoped endpoints validate both merchantId and mode to prevent
 * TEST/LIVE data leakage across environments.
 */
@RestController
public class LedgerQueryController {

    private final LedgerQueryService service;

    public LedgerQueryController(LedgerQueryService service) {
        this.service = service;
    }

    /**
     * Paginated ledger entry history for an account, newest first.
     * One partition hit — ledger_account_id is the shard key.
     */
    @GetMapping("/v1/ledger/accounts/{ledgerAccountId}/entries")
    public ResponseEntity<PagedResult<LedgerEntryResponse>> listEntries(
            @PathVariable String ledgerAccountId,
            @RequestParam String merchantId,
            @RequestParam Mode mode,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listEntries(ledgerAccountId, merchantId, mode, page, size));
    }

    /**
     * Full transaction detail including all ledger legs (TENANT + PLATFORM/EXTERNAL).
     * Fans out across all 64 va_ledger_entry partitions — audit path only.
     */
    @GetMapping("/v1/ledger/transactions/{transactionId}")
    public ResponseEntity<TransactionDetailResponse> getTransaction(
            @PathVariable String transactionId,
            @RequestParam String merchantId,
            @RequestParam Mode mode) {
        return ResponseEntity.ok(service.getTransactionDetail(transactionId, merchantId, mode));
    }

    /**
     * Platform-wide trial balance — no merchant scope; admin only.
     * balanced=false signals a double-entry invariant violation or unbalanced seed data.
     * Known limit: unbounded query; add pagination when account count grows significantly.
     */
    @GetMapping("/internal/ledger/trial-balance")
    public ResponseEntity<TrialBalanceResponse> getTrialBalance(
            @RequestParam Mode mode,
            @RequestParam String asset) {
        return ResponseEntity.ok(service.getTrialBalance(mode, asset));
    }

    /**
     * Period account statement computed from signed entry sums filtered by effective_date.
     * opening = balance strictly before fromDate; closing = balance up to and including toDate.
     * One partition hit for each of the three queries.
     */
    @GetMapping("/v1/ledger/accounts/{ledgerAccountId}/statement")
    public ResponseEntity<AccountStatementResponse> getStatement(
            @PathVariable String ledgerAccountId,
            @RequestParam String merchantId,
            @RequestParam Mode mode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getStatement(ledgerAccountId, merchantId, mode, from, to));
    }
}
