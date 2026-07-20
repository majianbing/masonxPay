package com.masonx.virtualaccount.account;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.account.dto.LedgerAccountResponse;
import com.masonx.virtualaccount.account.dto.CreateLedgerAccountRequest;
import com.masonx.virtualaccount.vcc.dto.PagedResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * VA account management API.
 *
 * <p>Two security zones:
 * <ul>
 *   <li>{@code POST /internal/va/accounts} — requires {@code X-Internal-Token} (platform admin).
 *   <li>{@code GET /v1/va/accounts/**} — requires {@code X-Internal-Token}; merchantId validated against account ownership.
 * </ul>
 */
@RestController
public class LedgerAccountController {

    private final LedgerAccountManagementService service;

    public LedgerAccountController(LedgerAccountManagementService service) {
        this.service = service;
    }

    /** Creates a TENANT account (WALLET, CASH, etc.) for a merchant. Admin-only. */
    @PostMapping("/internal/va/accounts")
    public ResponseEntity<LedgerAccountResponse> create(@Valid @RequestBody CreateLedgerAccountRequest req) {
        return ResponseEntity.ok(service.createAccount(req));
    }

    /** Returns a single account by ID with live balance. */
    @GetMapping("/v1/va/accounts/{ledgerAccountId}")
    public ResponseEntity<LedgerAccountResponse> get(@PathVariable String ledgerAccountId,
                                               @RequestParam String merchantId) {
        return ResponseEntity.ok(service.getAccount(ledgerAccountId, merchantId));
    }

    /** Lists all TENANT accounts for a merchant, paginated. */
    @GetMapping("/v1/va/accounts")
    public ResponseEntity<PagedResult<LedgerAccountResponse>> list(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "TEST") Mode mode,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listAccounts(merchantId, mode, page, Math.min(size, 100)));
    }
}
