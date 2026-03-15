package com.masonx.paygateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyType;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.event.PaymentGatewayEvent;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.StripePaymentProviderService;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import com.masonx.paygateway.web.dto.ConfirmPaymentIntentRequest;
import com.masonx.paygateway.web.dto.CreatePaymentIntentRequest;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import com.masonx.paygateway.service.ProviderAccountService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class PaymentIntentService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final RoutingEngine routingEngine;
    private final StripePaymentProviderService stripeProvider;
    private final ProviderAccountService providerAccountService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentIntentService(PaymentIntentRepository paymentIntentRepository,
                                PaymentRequestRepository paymentRequestRepository,
                                RoutingEngine routingEngine,
                                StripePaymentProviderService stripeProvider,
                                ProviderAccountService providerAccountService,
                                ObjectMapper objectMapper,
                                ApplicationEventPublisher eventPublisher) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.routingEngine = routingEngine;
        this.stripeProvider = stripeProvider;
        this.providerAccountService = providerAccountService;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public PaymentIntentResponse create(ApiKeyAuthentication auth, CreatePaymentIntentRequest req) {
        requireSecretKey(auth);

        // Idempotency: return existing intent if key already used
        Optional<PaymentIntent> existing = paymentIntentRepository
                .findByMerchantIdAndIdempotencyKey(auth.getMerchantId(), req.idempotencyKey());
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        PaymentIntent intent = new PaymentIntent();
        intent.setMerchantId(auth.getMerchantId());
        intent.setMode(auth.getMode());
        intent.setAmount(req.amount());
        intent.setCurrency(req.currency().toUpperCase());
        intent.setIdempotencyKey(req.idempotencyKey());
        intent.setCaptureMethod(
                req.captureMethod() != null ? CaptureMethod.valueOf(req.captureMethod().toUpperCase())
                        : CaptureMethod.AUTOMATIC);
        intent.setSuccessUrl(req.successUrl());
        intent.setCancelUrl(req.cancelUrl());
        intent.setFailureUrl(req.failureUrl());
        intent.setMetadata(serializeMetadata(req.metadata()));
        intent.setStatus(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);

        return toResponse(paymentIntentRepository.save(intent));
    }

    @Transactional(readOnly = true)
    public PaymentIntentResponse get(ApiKeyAuthentication auth, UUID intentId) {
        return toResponse(loadOwned(auth, intentId));
    }

    public PaymentIntentResponse confirm(ApiKeyAuthentication auth, UUID intentId,
                                         ConfirmPaymentIntentRequest req) {
        requireSecretKey(auth);
        PaymentIntent intent = loadOwned(auth, intentId);

        if (intent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                && intent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
            throw new IllegalStateException(
                    "PaymentIntent cannot be confirmed in status: " + intent.getStatus());
        }

        // Resolve provider brand then pick a specific account (weighted-random)
        PaymentProvider provider = routingEngine.resolve(
                auth.getMerchantId(), intent.getAmount(), intent.getCurrency(), null,
                req.paymentMethodType() != null ? req.paymentMethodType() : "card");

        ProviderAccount account = routingEngine.resolveAccount(auth.getMerchantId(), provider, auth.getMode())
                .orElseThrow(() -> new IllegalStateException("No active connector for provider: " + provider));

        intent.setStatus(PaymentIntentStatus.PROCESSING);
        intent.setResolvedProvider(provider);
        intent.setConnectorAccountId(account.getId());
        paymentIntentRepository.save(intent);

        // Record the attempt
        PaymentRequest attempt = new PaymentRequest();
        attempt.setPaymentIntentId(intent.getId());
        attempt.setAmount(intent.getAmount());
        attempt.setCurrency(intent.getCurrency());
        attempt.setPaymentMethodType(req.paymentMethodType() != null ? req.paymentMethodType() : "card");
        attempt = paymentRequestRepository.save(attempt);

        // Use the pre-selected account's key — same account will be used for refunds/captures
        String providerSecretKey = providerAccountService.resolveSecretKeyById(account.getId());

        // Charge
        ChargeResult result = stripeProvider.charge(new ChargeRequest(
                intent.getId(), intent.getAmount(), intent.getCurrency(),
                attempt.getPaymentMethodType(), req.paymentMethodId(),
                "pi-" + intent.getId() + "-" + attempt.getId(),
                providerSecretKey
        ));

        // Update attempt
        attempt.setStatus(result.success() ? PaymentRequestStatus.SUCCEEDED : PaymentRequestStatus.FAILED);
        attempt.setProviderRequestId(result.providerPaymentId());
        attempt.setProviderResponse(result.providerResponseJson());
        attempt.setFailureCode(result.failureCode());
        attempt.setFailureMessage(result.failureMessage());
        paymentRequestRepository.save(attempt);

        // Update intent
        if (result.success()) {
            intent.setStatus(PaymentIntentStatus.SUCCEEDED);
            intent.setProviderPaymentId(result.providerPaymentId());
            intent.setProviderResponse(result.providerResponseJson());
        } else {
            intent.setStatus(PaymentIntentStatus.FAILED);
        }
        PaymentIntentResponse response = toResponse(paymentIntentRepository.save(intent));

        String eventType = result.success() ? "payment_intent.succeeded" : "payment_intent.failed";
        publishEvent(intent, eventType, response);

        return response;
    }

    public PaymentIntentResponse cancel(ApiKeyAuthentication auth, UUID intentId) {
        PaymentIntent intent = loadOwned(auth, intentId);

        if (intent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                && intent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
            throw new IllegalStateException(
                    "PaymentIntent cannot be canceled in status: " + intent.getStatus());
        }

        intent.setStatus(PaymentIntentStatus.CANCELED);
        PaymentIntentResponse response = toResponse(paymentIntentRepository.save(intent));
        publishEvent(intent, "payment_intent.canceled", response);
        return response;
    }

    // --- helpers ---

    private PaymentIntent loadOwned(ApiKeyAuthentication auth, UUID intentId) {
        return paymentIntentRepository.findByIdAndMerchantId(intentId, auth.getMerchantId())
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found"));
    }

    private void requireSecretKey(ApiKeyAuthentication auth) {
        if (auth.getType() != ApiKeyType.SECRET) {
            throw new AccessDeniedException("A secret API key is required for this operation");
        }
    }

    private PaymentIntentResponse toResponse(PaymentIntent intent) {
        List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
        return PaymentIntentResponse.from(intent, attempts, objectMapper);
    }

    private void publishEvent(PaymentIntent intent, String eventType, PaymentIntentResponse payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            eventPublisher.publishEvent(
                    new PaymentGatewayEvent(this, intent.getMerchantId(), eventType, intent.getId(), json));
        } catch (JsonProcessingException e) {
            // non-critical — log and move on
        }
    }

    @SuppressWarnings("unchecked")
    private String serializeMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
