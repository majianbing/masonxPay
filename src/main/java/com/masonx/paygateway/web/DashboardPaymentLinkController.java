package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.service.PaymentLinkService;
import com.masonx.paygateway.web.dto.CreatePaymentLinkRequest;
import com.masonx.paygateway.web.dto.PaymentLinkResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/payment-links")
public class DashboardPaymentLinkController {

    private final PaymentLinkService service;

    public DashboardPaymentLinkController(PaymentLinkService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT_LINK', 'READ')")
    public ResponseEntity<List<PaymentLinkResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "TEST") String mode) {
        return ResponseEntity.ok(service.list(merchantId, ApiKeyMode.valueOf(mode.toUpperCase())));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT_LINK', 'CREATE')")
    public ResponseEntity<PaymentLinkResponse> create(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "TEST") String mode,
            @Valid @RequestBody CreatePaymentLinkRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), req));
    }

    @DeleteMapping("/{linkId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT_LINK', 'DELETE')")
    public ResponseEntity<Void> deactivate(
            @PathVariable UUID merchantId,
            @PathVariable UUID linkId) {
        service.deactivate(merchantId, linkId);
        return ResponseEntity.noContent().build();
    }
}
