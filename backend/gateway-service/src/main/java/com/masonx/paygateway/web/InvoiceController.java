package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.service.billing.InvoicePaymentService;
import com.masonx.paygateway.service.billing.SubscriptionInvoiceService;
import com.masonx.paygateway.web.dto.InvoicePaymentResponse;
import com.masonx.paygateway.web.dto.InvoiceResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/invoices")
public class InvoiceController {

    private final SubscriptionInvoiceService invoiceService;
    private final InvoicePaymentService paymentService;

    public InvoiceController(SubscriptionInvoiceService invoiceService,
                             InvoicePaymentService paymentService) {
        this.invoiceService = invoiceService;
        this.paymentService = paymentService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'READ')")
    public ResponseEntity<List<InvoiceResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) UUID subscriptionId,
            @RequestParam(required = false, defaultValue = "TEST") String mode) {
        return ResponseEntity.ok(invoiceService.listAll(merchantId, subscriptionId,
                ApiKeyMode.valueOf(mode.toUpperCase())));
    }

    @GetMapping("/{invoiceId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'READ')")
    public ResponseEntity<InvoiceResponse> get(@PathVariable UUID merchantId,
                                               @PathVariable UUID invoiceId) {
        return ResponseEntity.ok(invoiceService.get(merchantId, invoiceId));
    }

    @PostMapping("/{invoiceId}/pay")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'CREATE')")
    public ResponseEntity<InvoicePaymentResponse> pay(@PathVariable UUID merchantId,
                                                      @PathVariable UUID invoiceId) {
        return ResponseEntity.ok(paymentService.pay(merchantId, invoiceId));
    }

    @PostMapping("/{invoiceId}/mark-uncollectible")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'SUBSCRIPTION', 'UPDATE')")
    public ResponseEntity<InvoiceResponse> markUncollectible(@PathVariable UUID merchantId,
                                                             @PathVariable UUID invoiceId) {
        return ResponseEntity.ok(invoiceService.markUncollectible(merchantId, invoiceId));
    }
}
