package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.StripePaymentProviderService;
import com.masonx.paygateway.service.EncryptionService;
import com.masonx.paygateway.service.ProviderAccountService;
import com.masonx.paygateway.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/connectors")
public class ConnectorController {

    private final ProviderAccountService service;
    private final ProviderAccountRepository providerAccountRepository;
    private final EncryptionService encryptionService;
    private final StripePaymentProviderService stripeProvider;

    public ConnectorController(ProviderAccountService service,
                               ProviderAccountRepository providerAccountRepository,
                               EncryptionService encryptionService,
                               StripePaymentProviderService stripeProvider) {
        this.service = service;
        this.providerAccountRepository = providerAccountRepository;
        this.encryptionService = encryptionService;
        this.stripeProvider = stripeProvider;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'READ')")
    public ResponseEntity<List<ProviderAccountResponse>> list(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(service.list(merchantId));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'CREATE')")
    public ResponseEntity<ProviderAccountResponse> create(@PathVariable UUID merchantId,
                                                          @Valid @RequestBody CreateProviderAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(merchantId, req));
    }

    @PatchMapping("/{accountId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'UPDATE')")
    public ResponseEntity<ProviderAccountResponse> update(@PathVariable UUID merchantId,
                                                          @PathVariable UUID accountId,
                                                          @Valid @RequestBody UpdateProviderAccountRequest req) {
        return ResponseEntity.ok(service.update(merchantId, accountId, req));
    }

    @DeleteMapping("/{accountId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID merchantId, @PathVariable UUID accountId) {
        service.delete(merchantId, accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{accountId}/set-primary")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'UPDATE')")
    public ResponseEntity<ProviderAccountResponse> setPrimary(@PathVariable UUID merchantId,
                                                               @PathVariable UUID accountId) {
        return ResponseEntity.ok(service.setPrimary(merchantId, accountId));
    }

    /**
     * Fire a live test charge against the connector's own provider key.
     * Uses Stripe's built-in test payment method tokens (pm_card_visa, etc.).
     * Nothing is persisted — this is a preview only.
     */
    @PostMapping("/{accountId}/preview")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'READ')")
    public ResponseEntity<PreviewPaymentResponse> preview(@PathVariable UUID merchantId,
                                                          @PathVariable UUID accountId,
                                                          @Valid @RequestBody PreviewPaymentRequest req) {
        ProviderAccount account = providerAccountRepository
                .findByIdAndMerchantId(accountId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found"));

        if (account.getStatus() != ProviderAccountStatus.ACTIVE) {
            throw new IllegalStateException("Connector is not active");
        }

        if (account.getProvider() != PaymentProvider.STRIPE) {
            return ResponseEntity.ok(new PreviewPaymentResponse(
                    false, "UNSUPPORTED", account.getProvider().name(), account.getLabel(),
                    req.amount(), req.currency(), null,
                    "preview_unsupported",
                    "Preview is currently only supported for Stripe connectors."));
        }

        String secretKey = encryptionService.decrypt(account.getEncryptedSecretKey());

        ChargeResult result = stripeProvider.charge(new ChargeRequest(
                UUID.randomUUID(),
                req.amount(),
                req.currency().toLowerCase(),
                "card",
                req.testCard(),
                "preview-" + UUID.randomUUID(),
                secretKey
        ));

        return ResponseEntity.ok(new PreviewPaymentResponse(
                result.success(),
                result.success() ? "SUCCEEDED" : "FAILED",
                account.getProvider().name(),
                account.getLabel(),
                req.amount(),
                req.currency().toUpperCase(),
                result.providerPaymentId(),
                result.failureCode(),
                result.failureMessage()
        ));
    }
}
