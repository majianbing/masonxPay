package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.service.billing.SubscriptionInvoiceService;
import com.masonx.paygateway.service.billing.SubscriptionService;
import com.masonx.paygateway.web.dto.CreateSubscriptionCheckoutLinkRequest;
import com.masonx.paygateway.web.dto.CreateSubscriptionRequest;
import com.masonx.paygateway.web.dto.InvoiceResponse;
import com.masonx.paygateway.web.dto.SubscriptionCheckoutLinkResponse;
import com.masonx.paygateway.web.dto.SubscriptionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/subscriptions")
public class SubscriptionController {

    private final SubscriptionService service;
    private final SubscriptionInvoiceService invoiceService;

    public SubscriptionController(SubscriptionService service,
                                  SubscriptionInvoiceService invoiceService) {
        this.service = service;
        this.invoiceService = invoiceService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'READ')")
    public ResponseEntity<List<SubscriptionResponse>> list(@PathVariable UUID merchantId,
                                                           @RequestParam(required = false, defaultValue = "TEST") String mode,
                                                           @RequestParam(required = false) UUID customerId) {
        return ResponseEntity.ok(service.list(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), customerId));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'CREATE')")
    public ResponseEntity<SubscriptionResponse> create(@PathVariable UUID merchantId,
                                                       @RequestParam(required = false, defaultValue = "TEST") String mode,
                                                       @Valid @RequestBody CreateSubscriptionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), request));
    }

    @GetMapping("/{subscriptionId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'READ')")
    public ResponseEntity<SubscriptionResponse> get(@PathVariable UUID merchantId,
                                                    @PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(service.get(merchantId, subscriptionId));
    }

    @GetMapping("/{subscriptionId}/checkout-links")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'READ')")
    public ResponseEntity<List<SubscriptionCheckoutLinkResponse>> listCheckoutLinks(
            @PathVariable UUID merchantId,
            @PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(service.listCheckoutLinks(merchantId, subscriptionId));
    }

    @PostMapping("/{subscriptionId}/checkout-links")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'CREATE')")
    public ResponseEntity<SubscriptionCheckoutLinkResponse> createCheckoutLink(
            @PathVariable UUID merchantId,
            @PathVariable UUID subscriptionId,
            @RequestBody(required = false) CreateSubscriptionCheckoutLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCheckoutLink(merchantId, subscriptionId, request));
    }

    @GetMapping("/{subscriptionId}/invoices")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'READ')")
    public ResponseEntity<List<InvoiceResponse>> listInvoices(
            @PathVariable UUID merchantId,
            @PathVariable UUID subscriptionId) {
        return ResponseEntity.ok(invoiceService.list(merchantId, subscriptionId));
    }

    @PostMapping("/{subscriptionId}/invoices/current-period")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'CREATE')")
    public ResponseEntity<InvoiceResponse> generateCurrentPeriodInvoice(
            @PathVariable UUID merchantId,
            @PathVariable UUID subscriptionId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(invoiceService.generateCurrentPeriodInvoice(merchantId, subscriptionId));
    }
}
