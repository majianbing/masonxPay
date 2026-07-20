package com.masonx.paygateway.web;

import com.masonx.paygateway.service.VirtualAccountDashboardService;
import com.masonx.paygateway.web.dto.VirtualAccountAccountsResponse;
import com.masonx.paygateway.web.dto.VirtualAccountLedgerAccountResponse;
import com.masonx.paygateway.web.dto.VirtualAccountLedgerEntryResponse;
import com.masonx.paygateway.web.dto.VirtualAccountPageResponse;
import com.masonx.paygateway.web.dto.VirtualAccountStatementResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/va")
public class VirtualAccountDashboardController {

    private final VirtualAccountDashboardService service;

    public VirtualAccountDashboardController(VirtualAccountDashboardService service) {
        this.service = service;
    }

    @GetMapping("/accounts")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<VirtualAccountAccountsResponse> listAccounts(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "TEST") String mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listAccounts(merchantId, mode, page, size));
    }

    @GetMapping("/accounts/{accountId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<VirtualAccountLedgerAccountResponse> getAccount(
            @PathVariable UUID merchantId,
            @PathVariable String accountId) {
        return ResponseEntity.ok(service.getAccount(merchantId, accountId));
    }

    @GetMapping("/accounts/{accountId}/entries")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<VirtualAccountPageResponse<VirtualAccountLedgerEntryResponse>> listEntries(
            @PathVariable UUID merchantId,
            @PathVariable String accountId,
            @RequestParam(defaultValue = "TEST") String mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(service.listEntries(merchantId, accountId, mode, page, size));
    }

    @GetMapping("/accounts/{accountId}/statement")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<VirtualAccountStatementResponse> getStatement(
            @PathVariable UUID merchantId,
            @PathVariable String accountId,
            @RequestParam(defaultValue = "TEST") String mode,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(service.getStatement(merchantId, accountId, mode, from, to));
    }
}
