package com.masonx.virtualaccount.account;

import com.masonx.virtualaccount.account.dto.AccountResponse;
import com.masonx.virtualaccount.account.dto.CreateAccountRequest;
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
 *   <li>{@code GET /v1/va/accounts/**} — merchant-facing (no auth beyond tenant scoping).
 * </ul>
 */
@RestController
public class VaAccountController {

    private final VaAccountManagementService service;

    public VaAccountController(VaAccountManagementService service) {
        this.service = service;
    }

    /** Creates a TENANT account (WALLET, CASH, etc.) for a merchant. Admin-only. */
    @PostMapping("/internal/va/accounts")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest req) {
        return ResponseEntity.ok(service.createAccount(req));
    }

    /** Returns a single account by ID with live balance. */
    @GetMapping("/v1/va/accounts/{accountId}")
    public ResponseEntity<AccountResponse> get(@PathVariable String accountId) {
        return ResponseEntity.ok(service.getAccount(accountId));
    }

    /** Lists all TENANT accounts for a merchant, paginated. */
    @GetMapping("/v1/va/accounts")
    public ResponseEntity<PagedResult<AccountResponse>> list(
            @RequestParam String merchantId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listAccounts(merchantId, page, Math.min(size, 100)));
    }
}
