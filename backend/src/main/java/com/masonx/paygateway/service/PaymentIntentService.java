package com.masonx.paygateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyType;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.web.TraceIdFilter;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.redis.PaymentIdempotencyCache;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import com.masonx.paygateway.service.routing.RoutingContext;
import com.masonx.paygateway.sharding.IdempotencyReservationStatus;
import com.masonx.paygateway.sharding.PaymentIdempotencyRoute;
import com.masonx.paygateway.sharding.PaymentShardRegistryRepository;
import com.masonx.paygateway.sharding.PaymentShardRouter;
import com.masonx.paygateway.web.dto.ConfirmPaymentIntentRequest;
import com.masonx.paygateway.web.dto.CreatePaymentIntentRequest;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Service
public class PaymentIntentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentService.class);

    @Value("${app.pay-base-url:http://localhost:3000}")
    private String payBaseUrl;

    private final PaymentIntentRepository    paymentIntentRepository;
    private final PaymentRequestRepository   paymentRequestRepository;
    private final RoutingEngine              routingEngine;
    private final PaymentProviderDispatcher  dispatcher;
    private final ProviderAccountService     providerAccountService;
    private final ProviderAccountRepository  providerAccountRepository;
    private final PaymentTokenService        paymentTokenService;
    private final PaymentRetryOrchestratorService retryOrchestrator;
    private final ObjectMapper               objectMapper;
    private final OutboxEventRepository      outboxEventRepository;
    private final PaymentShardRegistryRepository shardRegistryRepository;
    private final PaymentShardRouter         shardRouter;
    private final PaymentIdempotencyCache    idempotencyCache;
    private final TransactionTemplate        txTemplate;
    private final PaymentMetrics             metrics;

    public PaymentIntentService(PaymentIntentRepository paymentIntentRepository,
                                PaymentRequestRepository paymentRequestRepository,
                                RoutingEngine routingEngine,
                                PaymentProviderDispatcher dispatcher,
                                ProviderAccountService providerAccountService,
                                ProviderAccountRepository providerAccountRepository,
                                PaymentTokenService paymentTokenService,
                                PaymentRetryOrchestratorService retryOrchestrator,
                                ObjectMapper objectMapper,
                                OutboxEventRepository outboxEventRepository,
                                PaymentShardRegistryRepository shardRegistryRepository,
                                PaymentShardRouter shardRouter,
                                PaymentIdempotencyCache idempotencyCache,
                                PlatformTransactionManager txManager,
                                PaymentMetrics metrics) {
        this.paymentIntentRepository  = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.routingEngine            = routingEngine;
        this.dispatcher               = dispatcher;
        this.providerAccountService   = providerAccountService;
        this.providerAccountRepository = providerAccountRepository;
        this.paymentTokenService      = paymentTokenService;
        this.retryOrchestrator        = retryOrchestrator;
        this.objectMapper             = objectMapper;
        this.outboxEventRepository    = outboxEventRepository;
        this.shardRegistryRepository  = shardRegistryRepository;
        this.shardRouter              = shardRouter;
        this.idempotencyCache         = idempotencyCache;
        this.txTemplate               = new TransactionTemplate(txManager);
        this.metrics                  = metrics;
    }

    @Transactional
    public PaymentIntentResponse create(ApiKeyAuthentication auth, CreatePaymentIntentRequest req) {
        requireSecretKey(auth);

        Optional<PaymentIdempotencyRoute> cachedRoute = idempotencyCache
                .find(auth.getMerchantId(), req.idempotencyKey());
        if (cachedRoute.isPresent()) {
            Optional<PaymentIntentResponse> cachedResponse = paymentIntentRepository
                    .findByIdAndMerchantId(cachedRoute.get().paymentIntentId(), auth.getMerchantId())
                    .map(this::toResponse);
            if (cachedResponse.isPresent()) {
                return cachedResponse.get();
            }
            log.warn("Redis idempotency cache pointed to missing payment intent {}", cachedRoute.get().paymentIntentId());
        }

        Optional<PaymentIdempotencyRoute> existingRoute = shardRegistryRepository
                .findIdempotencyRoute(auth.getMerchantId(), req.idempotencyKey());
        if (existingRoute.isPresent()) {
            PaymentIdempotencyRoute route = existingRoute.get();
            idempotencyCache.put(route);
            return paymentIntentRepository.findByIdAndMerchantId(route.paymentIntentId(), auth.getMerchantId())
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException(
                            "Payment creation is still in progress for this idempotency key"));
        }

        UUID paymentIntentId = UUID.randomUUID();
        int paymentShardId = shardRouter.shardForPaymentId(paymentIntentId);
        boolean reserved = shardRegistryRepository.reserveIdempotencyKey(
                auth.getMerchantId(), req.idempotencyKey(), paymentIntentId, paymentShardId);
        if (!reserved) {
            return shardRegistryRepository.findIdempotencyRoute(auth.getMerchantId(), req.idempotencyKey())
                    .flatMap(route -> paymentIntentRepository
                            .findByIdAndMerchantId(route.paymentIntentId(), auth.getMerchantId()))
                    .map(this::toResponse)
                    .orElseThrow(() -> new IllegalStateException(
                            "Payment creation is already in progress for this idempotency key"));
        }

        PaymentIntent intent = new PaymentIntent();
        intent.assignId(paymentIntentId);
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
        intent.setOrderId(req.orderId());
        intent.setDescription(req.description());
        intent.setBillingDetails(req.billingDetails());
        intent.setShippingDetails(req.shippingDetails());
        intent.setTraceId(MDC.get(TraceIdFilter.MDC_KEY));
        intent.setStatus(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);

        PaymentIntent saved = paymentIntentRepository.save(intent);
        shardRegistryRepository.updateIdempotencyStatus(
                auth.getMerchantId(), req.idempotencyKey(), IdempotencyReservationStatus.COMPLETED);
        PaymentIdempotencyRoute completedRoute = new PaymentIdempotencyRoute(
                auth.getMerchantId(),
                req.idempotencyKey(),
                saved.getId(),
                paymentShardId,
                IdempotencyReservationStatus.COMPLETED);
        cacheIdempotencyRouteAfterCommit(completedRoute);
        return toResponse(saved);
    }

    private void cacheIdempotencyRouteAfterCommit(PaymentIdempotencyRoute route) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            idempotencyCache.put(route);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                idempotencyCache.put(route);
            }
        });
    }

    @Transactional(readOnly = true)
    public PaymentIntentResponse get(ApiKeyAuthentication auth, UUID intentId) {
        return toResponse(loadOwned(auth, intentId));
    }

    /**
     * Confirms a payment intent against the provider.
     *
     * This method deliberately has NO @Transactional annotation.
     * Remote calls to Stripe/Square must never run inside a database transaction —
     * holding a connection open during a network call would exhaust the pool under load.
     * Instead, each DB write phase is wrapped in its own short TransactionTemplate block.
     *
     * TX flow:
     *   1. TX-setup   — validate intent, build RoutePlan, set PROCESSING, save intent
     *   2. orchestrator — bounded retry execution, with each attempt recorded in short TX blocks
     *   3. TX-finalize — re-read intent, set final status, write OutboxEvent, save
     */
    public PaymentIntentResponse confirm(ApiKeyAuthentication auth, UUID intentId,
                                         ConfirmPaymentIntentRequest req) {
        requireSecretKey(auth);

        // --- TX 1: validate, resolve routing, mark PROCESSING ---
        record Setup(PaymentIntent intent, RoutePlan routePlan, String rawPmId, String pmType) {}

        Setup setup = txTemplate.execute(ts -> {
            PaymentIntent intent = loadOwnedForUpdate(auth, intentId);

            if (intent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                    && intent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
                throw new IllegalStateException(
                        "PaymentIntent cannot be confirmed in status: " + intent.getStatus());
            }

            final ProviderAccount firstAccount;
            final String rawPmId;
            final RoutePlan routePlan;

            if (req.paymentMethodId().startsWith("gw_tok_")) {
                PaymentToken token = paymentTokenService.consume(req.paymentMethodId());
                if (!token.getMerchantId().equals(auth.getMerchantId())) {
                    throw new AccessDeniedException("Payment token does not belong to this merchant");
                }
                firstAccount = providerAccountRepository.findById(token.getAccountId())
                        .orElseThrow(() -> new IllegalStateException("Connector account not found"));
                PaymentProvider tokenProvider = PaymentProvider.valueOf(token.getProvider());
                if (firstAccount.getProvider() != tokenProvider) {
                    throw new IllegalStateException("Payment token provider does not match connector account");
                }
                rawPmId = token.getProviderPmId();
                routePlan = RoutePlan.single(firstAccount);
            } else {
                String pmType = req.paymentMethodType() != null ? req.paymentMethodType() : "card";
                RoutingContext routingContext = new RoutingContext(
                        auth.getMerchantId(),
                        auth.getMode(),
                        intent.getAmount(),
                        intent.getCurrency(),
                        null,
                        pmType,
                        intent.getCaptureMethod(),
                        null,
                        intent.getOrderId(),
                        parseMetadata(intent.getMetadata()),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );
                routePlan = routingEngine.resolvePlan(routingContext)
                        .orElseThrow(() -> new IllegalStateException("No active payment connector configured"));
                firstAccount = routePlan.first().account();
                rawPmId = req.paymentMethodId();
            }

            intent.setPaymentMethodType(req.paymentMethodType() != null ? req.paymentMethodType() : "card");
            if (req.billingDetails() != null) intent.setBillingDetails(req.billingDetails());
            if (req.shippingDetails() != null) intent.setShippingDetails(req.shippingDetails());
            intent.setStatus(PaymentIntentStatus.PROCESSING);
            intent.setResolvedProvider(firstAccount.getProvider());
            intent.setConnectorAccountId(firstAccount.getId());
            paymentIntentRepository.save(intent);

            String pmType = req.paymentMethodType() != null ? req.paymentMethodType() : "card";
            return new Setup(intent, routePlan, rawPmId, pmType);
        });

        PaymentRetryOrchestratorService.Result retryResult = retryOrchestrator.execute(
                setup.intent(), setup.rawPmId(), setup.pmType(), setup.routePlan(),
                PaymentRetryContext.sameAccountOnly());

        // --- TX 3: finalize intent status + write outbox event atomically ---
        final ChargeResult finalResult = retryResult.lastResult();

        // Capture trace ID from MDC before entering the TX (MDC is thread-local)
        final String traceId = MDC.get(TraceIdFilter.MDC_KEY);

        return txTemplate.execute(ts -> {
            PaymentIntent intent = paymentIntentRepository.findByIdForUpdate(setup.intent().getId()).orElseThrow();

            final String eventType;
            if (finalResult != null && finalResult.success()) {
                // MANUAL capture: authorized but not yet settled
                boolean manualCapture = intent.getCaptureMethod() == CaptureMethod.MANUAL;
                intent.setStatus(manualCapture
                        ? PaymentIntentStatus.REQUIRES_CAPTURE
                        : PaymentIntentStatus.SUCCEEDED);
                intent.setResolvedProvider(retryResult.usedCandidate().provider());
                intent.setConnectorAccountId(retryResult.accountId());
                intent.setProviderPaymentId(finalResult.providerPaymentId());
                intent.setProviderResponse(finalResult.providerResponseJson());
                eventType = manualCapture ? "payment_intent.requires_capture" : "payment_intent.succeeded";
            } else {
                intent.setStatus(PaymentIntentStatus.FAILED);
                if (retryResult.usedCandidate() != null) {
                    intent.setResolvedProvider(retryResult.usedCandidate().provider());
                    intent.setConnectorAccountId(retryResult.accountId());
                }
                eventType = "payment_intent.failed";
            }

            if (intent.getTraceId() == null && traceId != null) {
                intent.setTraceId(traceId);
            }

            PaymentIntent saved = paymentIntentRepository.save(intent);
            List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(saved.getId());
            PaymentIntentResponse response = PaymentIntentResponse.from(saved, attempts, objectMapper, null);

            // Write outbox event in the same TX as the intent save — atomic
            writeOutboxEvent(saved.getMerchantId(), eventType, saved.getId(), response);

            // Record metric outside the critical path (cheap — just increment a counter)
            String failureCode = (finalResult != null) ? finalResult.failureCode() : null;
            metrics.recordIntentConfirmed(
                    retryResult.providerName(),
                    saved.getStatus().name(),
                    failureCode);

            return response;
        });
    }

    /**
     * Captures an authorized payment intent (status REQUIRES_CAPTURE).
     * No @Transactional — the provider capture call must not hold a DB connection.
     */
    public PaymentIntentResponse capture(ApiKeyAuthentication auth, UUID intentId) {
        requireSecretKey(auth);

        record CaptureSetup(PaymentIntent intent, ProviderCredentials creds) {}

        CaptureSetup setup = txTemplate.execute(ts -> {
            PaymentIntent intent = loadOwnedForUpdate(auth, intentId);
            if (intent.getStatus() != PaymentIntentStatus.REQUIRES_CAPTURE) {
                throw new IllegalStateException(
                        "PaymentIntent cannot be captured in status: " + intent.getStatus());
            }
            if (intent.getConnectorAccountId() == null || intent.getProviderPaymentId() == null) {
                throw new IllegalStateException("PaymentIntent has no provider payment ID to capture");
            }
            ProviderCredentials creds = providerAccountService.loadCredentials(intent.getConnectorAccountId());
            return new CaptureSetup(intent, creds);
        });

        // Remote call — outside any transaction
        boolean captured = dispatcher.captureAtProvider(
                setup.intent().getResolvedProvider(),
                setup.intent().getProviderPaymentId(),
                setup.creds());

        // TX: update status + write outbox event
        return txTemplate.execute(ts -> {
            PaymentIntent intent = paymentIntentRepository.findByIdForUpdate(setup.intent().getId()).orElseThrow();
            // Race guard: only update if still waiting for capture
            if (intent.getStatus() == PaymentIntentStatus.REQUIRES_CAPTURE) {
                intent.setStatus(captured ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
                intent = paymentIntentRepository.save(intent);
                String eventType = captured ? "payment_intent.succeeded" : "payment_intent.failed";
                List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
                PaymentIntentResponse response = PaymentIntentResponse.from(intent, attempts, objectMapper, null);
                writeOutboxEvent(intent.getMerchantId(), eventType, intent.getId(), response);
                metrics.recordCaptureAttempted(
                        setup.intent().getResolvedProvider() != null
                                ? setup.intent().getResolvedProvider().name() : null,
                        captured);
                return response;
            }
            List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
            return PaymentIntentResponse.from(intent, attempts, objectMapper, null);
        });
    }

    /**
     * Cancels a payment intent.
     * For REQUIRES_CAPTURE intents, also releases the authorization hold at the provider.
     * No @Transactional — may include a remote call when canceling a REQUIRES_CAPTURE intent.
     */
    public PaymentIntentResponse cancel(ApiKeyAuthentication auth, UUID intentId) {
        requireSecretKey(auth);

        // TX 1: validate and snapshot
        PaymentIntent snapshot = txTemplate.execute(ts -> {
            PaymentIntent intent = loadOwnedForUpdate(auth, intentId);
            if (intent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                    && intent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION
                    && intent.getStatus() != PaymentIntentStatus.REQUIRES_CAPTURE
                    && intent.getStatus() != PaymentIntentStatus.REQUIRES_ACTION) {
                throw new IllegalStateException(
                        "PaymentIntent cannot be canceled in status: " + intent.getStatus());
            }
            return intent;
        });

        // If authorized but not captured, release the hold at the provider (best-effort)
        if (snapshot.getStatus() == PaymentIntentStatus.REQUIRES_CAPTURE
                && snapshot.getProviderPaymentId() != null
                && snapshot.getConnectorAccountId() != null) {
            try {
                ProviderCredentials creds = providerAccountService.loadCredentials(
                        snapshot.getConnectorAccountId());
                dispatcher.cancelAtProvider(snapshot.getResolvedProvider(),
                        snapshot.getProviderPaymentId(), creds);
            } catch (Exception e) {
                log.warn("Failed to release authorization hold for intent {}: {}",
                        intentId, e.getMessage());
                // Continue to cancel locally regardless — the hold will expire at the provider
            }
        }

        // TX 2: set CANCELED + write outbox event
        return txTemplate.execute(ts -> {
            PaymentIntent intent = paymentIntentRepository.findByIdForUpdate(snapshot.getId()).orElseThrow();
            // Race guard: re-validate status
            if (intent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                    && intent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION
                    && intent.getStatus() != PaymentIntentStatus.REQUIRES_CAPTURE
                    && intent.getStatus() != PaymentIntentStatus.REQUIRES_ACTION) {
                throw new IllegalStateException(
                        "PaymentIntent cannot be canceled in status: " + intent.getStatus());
            }
            intent.setStatus(PaymentIntentStatus.CANCELED);
            intent = paymentIntentRepository.save(intent);
            List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
            PaymentIntentResponse response = PaymentIntentResponse.from(intent, attempts, objectMapper, null);
            writeOutboxEvent(intent.getMerchantId(), "payment_intent.canceled", intent.getId(), response);
            return response;
        });
    }

    // --- helpers ---

    private PaymentIntent loadOwned(ApiKeyAuthentication auth, UUID intentId) {
        return paymentIntentRepository.findByIdAndMerchantId(intentId, auth.getMerchantId())
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found"));
    }

    private PaymentIntent loadOwnedForUpdate(ApiKeyAuthentication auth, UUID intentId) {
        return paymentIntentRepository.findByIdAndMerchantIdForUpdate(intentId, auth.getMerchantId())
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found"));
    }

    private void requireSecretKey(ApiKeyAuthentication auth) {
        if (auth.getType() != ApiKeyType.SECRET) {
            throw new AccessDeniedException("A secret API key is required for this operation");
        }
    }

    private PaymentIntentResponse toResponse(PaymentIntent intent) {
        List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
        return PaymentIntentResponse.from(intent, attempts, objectMapper, null);
    }

    /**
     * Writes an OutboxEvent row in the CURRENT transaction (must be called from within a
     * txTemplate.execute() or @Transactional context). The event is picked up by
     * WebhookDeliveryService.processOutbox() and dispatched to merchant webhook endpoints.
     *
     * Failure to serialize the payload is non-fatal: the payment state is already persisted,
     * and the worst case is that the merchant webhook is not delivered for this event.
     */
    private void writeOutboxEvent(UUID merchantId, String eventType, UUID resourceId,
                                   PaymentIntentResponse payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            outboxEventRepository.save(new OutboxEvent(merchantId, eventType, resourceId, json));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize outbox payload for event {} on intent {}: {}",
                    eventType, resourceId, e.getMessage());
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

    private Map<String, String> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadata, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
