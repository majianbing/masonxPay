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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
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

    @GetMapping("/issuer-partners")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<Object> listIssuerPartners(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "TEST") String mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listIssuerPartners(merchantId, mode, page, size);
    }

    @GetMapping("/card-programs")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<Object> listCardPrograms(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "TEST") String mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listCardPrograms(merchantId, mode, page, size);
    }

    @PostMapping("/card-programs")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'CREATE')")
    public ResponseEntity<Object> createCardProgram(
            @PathVariable UUID merchantId,
            @RequestBody Map<String, Object> body) {
        return service.createCardProgram(merchantId, body);
    }

    @GetMapping("/cardholders")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<Object> listCardholders(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "TEST") String mode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listCardholders(merchantId, mode, page, size);
    }

    @PostMapping("/cardholders")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'CREATE')")
    public ResponseEntity<Object> createCardholder(
            @PathVariable UUID merchantId,
            @RequestBody Map<String, Object> body) {
        return service.createCardholder(merchantId, body);
    }

    @GetMapping("/cards")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<Object> listCards(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listCards(merchantId, page, size);
    }

    @PostMapping("/cards")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'CREATE')")
    public ResponseEntity<Object> createCard(
            @PathVariable UUID merchantId,
            @RequestBody Map<String, Object> body) {
        return service.createCard(merchantId, body);
    }

    @PostMapping("/cards/{cardId}/fund")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'EXECUTE')")
    public ResponseEntity<Object> fundCard(
            @PathVariable UUID merchantId,
            @PathVariable String cardId,
            @RequestBody Map<String, Object> body) {
        return service.fundCard(merchantId, cardId, body);
    }

    @PostMapping("/cards/{cardId}/withdraw")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'EXECUTE')")
    public ResponseEntity<Object> withdrawCard(
            @PathVariable UUID merchantId,
            @PathVariable String cardId,
            @RequestBody Map<String, Object> body) {
        return service.withdrawCard(merchantId, cardId, body);
    }

    @PostMapping("/cards/{cardId}/{action:lock|unlock|close|terminate}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'EXECUTE')")
    public ResponseEntity<Object> cardLifecycle(
            @PathVariable UUID merchantId,
            @PathVariable String cardId,
            @PathVariable String action,
            @RequestBody(required = false) Map<String, Object> body) {
        return service.cardLifecycle(merchantId, cardId, action, body);
    }
}
