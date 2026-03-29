package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.service.ProviderAccountService;
import com.masonx.paygateway.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/connectors")
public class ConnectorController {

    private final ProviderAccountService service;
    private final ProviderAccountRepository providerAccountRepository;
    private final CredentialsCodec credentialsCodec;
    private final PaymentProviderDispatcher dispatcher;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentLinkRepository paymentLinkRepository;

    public ConnectorController(ProviderAccountService service,
                               ProviderAccountRepository providerAccountRepository,
                               CredentialsCodec credentialsCodec,
                               PaymentProviderDispatcher dispatcher,
                               PaymentIntentRepository paymentIntentRepository,
                               PaymentRequestRepository paymentRequestRepository,
                               PaymentLinkRepository paymentLinkRepository) {
        this.service = service;
        this.providerAccountRepository = providerAccountRepository;
        this.credentialsCodec = credentialsCodec;
        this.dispatcher = dispatcher;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.paymentLinkRepository = paymentLinkRepository;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'READ')")
    public ResponseEntity<List<ProviderAccountResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "TEST") String mode) {
        ApiKeyMode modeEnum = ApiKeyMode.valueOf(mode.toUpperCase());
        return ResponseEntity.ok(service.list(merchantId, modeEnum));
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

    @PutMapping("/reorder")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'UPDATE')")
    public ResponseEntity<Void> reorder(@PathVariable UUID merchantId,
                                        @Valid @RequestBody ReorderConnectorsRequest req) {
        service.reorder(merchantId, req.items());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{accountId}/set-primary")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'UPDATE')")
    public ResponseEntity<ProviderAccountResponse> setPrimary(@PathVariable UUID merchantId,
                                                               @PathVariable UUID accountId) {
        return ResponseEntity.ok(service.setPrimary(merchantId, accountId));
    }

    /**
     * Fire a test charge against the connector's own provider key.
     * Uses Stripe's built-in test payment method tokens (pm_card_visa, etc.).
     * Persisted as a TEST-mode PaymentIntent so it appears in the dashboard under TEST data.
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

        if (account.getMode() != ApiKeyMode.TEST) {
            throw new IllegalStateException("Preview is only available for TEST mode connectors");
        }

        if (account.getProvider() != PaymentProvider.STRIPE) {
            return ResponseEntity.ok(new PreviewPaymentResponse(
                    false, "UNSUPPORTED", account.getProvider().name(), account.getLabel(),
                    req.amount(), req.currency(), null,
                    "preview_unsupported",
                    "Preview test cards are currently only supported for Stripe connectors."));
        }

        ProviderCredentials creds = credentialsCodec.decode(account);
        UUID intentId = UUID.randomUUID();
        String idempotencyKey = "preview-" + UUID.randomUUID();

        ChargeResult result = dispatcher.charge(account.getProvider(), new ChargeRequest(
                intentId,
                req.amount(),
                req.currency().toLowerCase(),
                "card",
                req.testCard(),
                idempotencyKey
        ), creds);

        // Persist preview charge as a TEST-mode PaymentIntent + PaymentRequest
        PaymentIntent intent = new PaymentIntent();
        intent.setMerchantId(merchantId);
        intent.setMode(ApiKeyMode.TEST);
        intent.setAmount(req.amount());
        intent.setCurrency(req.currency().toLowerCase());
        intent.setStatus(result.success() ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
        intent.setResolvedProvider(account.getProvider());
        intent.setConnectorAccountId(account.getId());
        intent.setIdempotencyKey(idempotencyKey);
        intent.setProviderPaymentId(result.providerPaymentId());
        PaymentIntent savedIntent = paymentIntentRepository.save(intent);

        PaymentRequest paymentRequest = new PaymentRequest();
        paymentRequest.setPaymentIntentId(savedIntent.getId());
        paymentRequest.setAmount(req.amount());
        paymentRequest.setCurrency(req.currency().toLowerCase());
        paymentRequest.setPaymentMethodType("card");
        paymentRequest.setStatus(result.success() ? PaymentRequestStatus.SUCCEEDED : PaymentRequestStatus.FAILED);
        paymentRequest.setProviderRequestId(result.providerPaymentId());
        paymentRequest.setFailureCode(result.failureCode());
        paymentRequest.setFailureMessage(result.failureMessage());
        paymentRequestRepository.save(paymentRequest);

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

    /**
     * Creates a short-lived TEST payment link pinned to a specific connector.
     * Used by the SDK-driven preview: checkout-session returns only this provider,
     * and tokenize bypasses routing in favour of this exact account.
     */
    @PostMapping("/{accountId}/preview-link")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'READ')")
    public ResponseEntity<Map<String, String>> previewLink(@PathVariable UUID merchantId,
                                                           @PathVariable UUID accountId,
                                                           @RequestBody PreviewLinkRequest req) {
        ProviderAccount account = providerAccountRepository
                .findByIdAndMerchantId(accountId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found"));

        if (account.getStatus() != ProviderAccountStatus.ACTIVE) {
            throw new IllegalStateException("Connector is not active");
        }
        if (account.getMode() != ApiKeyMode.TEST) {
            throw new IllegalStateException("Preview is only available for TEST mode connectors");
        }

        PaymentLink link = new PaymentLink();
        link.setMerchantId(merchantId);
        link.setToken("prev_" + UUID.randomUUID().toString().replace("-", ""));
        link.setTitle("Preview");
        link.setAmount(req.amount());
        link.setCurrency(req.currency().toLowerCase());
        link.setMode(ApiKeyMode.TEST);
        link.setPinnedConnectorId(accountId);
        link.setExpiresAt(Instant.now().plus(15, ChronoUnit.MINUTES));
        paymentLinkRepository.save(link);

        return ResponseEntity.ok(Map.of("linkToken", link.getToken()));
    }
}
