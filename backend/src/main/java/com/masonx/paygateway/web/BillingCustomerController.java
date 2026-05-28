package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.service.billing.BillingCustomerService;
import com.masonx.paygateway.web.dto.AttachCustomerPaymentMethodRequest;
import com.masonx.paygateway.web.dto.BillingCustomerRequest;
import com.masonx.paygateway.web.dto.BillingCustomerResponse;
import com.masonx.paygateway.web.dto.CustomerPaymentMethodResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/customers")
public class BillingCustomerController {

    private final BillingCustomerService service;

    public BillingCustomerController(BillingCustomerService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CUSTOMER', 'READ')")
    public ResponseEntity<List<BillingCustomerResponse>> list(@PathVariable UUID merchantId,
                                                              @RequestParam(required = false, defaultValue = "TEST") String mode) {
        return ResponseEntity.ok(service.list(merchantId, ApiKeyMode.valueOf(mode.toUpperCase())));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CUSTOMER', 'CREATE')")
    public ResponseEntity<BillingCustomerResponse> create(@PathVariable UUID merchantId,
                                                          @RequestParam(required = false, defaultValue = "TEST") String mode,
                                                          @Valid @RequestBody BillingCustomerRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), request));
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CUSTOMER', 'READ')")
    public ResponseEntity<BillingCustomerResponse> get(@PathVariable UUID merchantId,
                                                       @RequestParam(required = false, defaultValue = "TEST") String mode,
                                                       @PathVariable UUID customerId) {
        return ResponseEntity.ok(service.get(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), customerId));
    }

    @PatchMapping("/{customerId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CUSTOMER', 'UPDATE')")
    public ResponseEntity<BillingCustomerResponse> update(@PathVariable UUID merchantId,
                                                          @PathVariable UUID customerId,
                                                          @RequestParam(required = false, defaultValue = "TEST") String mode,
                                                          @Valid @RequestBody BillingCustomerRequest request) {
        return ResponseEntity.ok(service.update(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), customerId, request));
    }

    @GetMapping("/{customerId}/payment-methods")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CUSTOMER', 'READ')")
    public ResponseEntity<List<CustomerPaymentMethodResponse>> listPaymentMethods(@PathVariable UUID merchantId,
                                                                                  @RequestParam(required = false, defaultValue = "TEST") String mode,
                                                                                  @PathVariable UUID customerId) {
        return ResponseEntity.ok(service.listPaymentMethods(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), customerId));
    }

    @PostMapping("/{customerId}/payment-methods")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CUSTOMER', 'UPDATE')")
    public ResponseEntity<CustomerPaymentMethodResponse> attachPaymentMethod(
            @PathVariable UUID merchantId,
            @PathVariable UUID customerId,
            @RequestParam(required = false, defaultValue = "TEST") String mode,
            @Valid @RequestBody AttachCustomerPaymentMethodRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.attachPaymentMethod(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), customerId, request));
    }

    @DeleteMapping("/{customerId}/payment-methods/{methodId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CUSTOMER', 'UPDATE')")
    public ResponseEntity<CustomerPaymentMethodResponse> detachPaymentMethod(
            @PathVariable UUID merchantId,
            @PathVariable UUID customerId,
            @RequestParam(required = false, defaultValue = "TEST") String mode,
            @PathVariable UUID methodId) {
        return ResponseEntity.ok(service.detachPaymentMethod(merchantId, ApiKeyMode.valueOf(mode.toUpperCase()), customerId, methodId));
    }
}
