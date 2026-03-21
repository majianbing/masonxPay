package com.masonx.paygateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyType;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.event.PaymentGatewayEvent;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import com.masonx.paygateway.web.dto.ConfirmPaymentIntentRequest;
import com.masonx.paygateway.web.dto.CreatePaymentIntentRequest;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentIntentService {

    private static final int MAX_FAILOVER_ATTEMPTS = 3;

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final RoutingEngine routingEngine;
    private final PaymentProviderDispatcher dispatcher;
    private final ProviderAccountService providerAccountService;
    private final ProviderAccountRepository providerAccountRepository;
    private final PaymentTokenService paymentTokenService;
    private final FailoverPolicy failoverPolicy;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;

    public PaymentIntentService(PaymentIntentRepository paymentIntentRepository,
                                PaymentRequestRepository paymentRequestRepository,
                                RoutingEngine routingEngine,
                                PaymentProviderDispatcher dispatcher,
                                ProviderAccountService providerAccountService,
                                ProviderAccountRepository providerAccountRepository,
                                PaymentTokenService paymentTokenService,
                                FailoverPolicy failoverPolicy,
                                ObjectMapper objectMapper,
                                ApplicationEventPublisher eventPublisher,
                                PlatformTransactionManager txManager) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.routingEngine = routingEngine;
        this.dispatcher = dispatcher;
        this.providerAccountService = providerAccountService;
        this.providerAccountRepository = providerAccountRepository;
        this.paymentTokenService = paymentTokenService;
        this.failoverPolicy = failoverPolicy;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @Transactional
    public PaymentIntentResponse create(ApiKeyAuthentication auth, CreatePaymentIntentRequest req) {
        requireSecretKey(auth);

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

    /**
     * Confirms a payment intent against the provider.
     *
     * This method deliberately has NO @Transactional annotation.
     * Remote calls to Stripe/Square must never run inside a database transaction —
     * holding a connection open during a network call would exhaust the pool under load.
     * Instead, each DB write phase is wrapped in its own short TransactionTemplate block.
     *
     * TX flow:
     *   1. TX-setup   — validate intent, resolve routing, set PROCESSING, save intent
     *   2. per attempt — TX-create-attempt → remote call → TX-update-attempt (repeated up to MAX_FAILOVER_ATTEMPTS)
     *   3. TX-finalize — re-read intent, set final status, save
     */
    public PaymentIntentResponse confirm(ApiKeyAuthentication auth, UUID intentId,
                                         ConfirmPaymentIntentRequest req) {
        requireSecretKey(auth);

        // --- TX 1: validate, resolve routing, mark PROCESSING ---
        record Setup(PaymentIntent intent, PaymentProvider provider,
                     List<ProviderAccount> accountQueue, String rawPmId, String pmType) {}

        Setup setup = txTemplate.execute(ts -> {
            PaymentIntent intent = loadOwned(auth, intentId);

            if (intent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                    && intent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
                throw new IllegalStateException(
                        "PaymentIntent cannot be confirmed in status: " + intent.getStatus());
            }

            final PaymentProvider provider;
            final ProviderAccount firstAccount;
            final String rawPmId;

            if (req.paymentMethodId().startsWith("gw_tok_")) {
                PaymentToken token = paymentTokenService.consume(req.paymentMethodId());
                if (!token.getMerchantId().equals(auth.getMerchantId())) {
                    throw new AccessDeniedException("Payment token does not belong to this merchant");
                }
                provider = PaymentProvider.valueOf(token.getProvider());
                firstAccount = providerAccountRepository.findById(token.getAccountId())
                        .orElseThrow(() -> new IllegalStateException("Connector account not found"));
                rawPmId = token.getProviderPmId();
            } else {
                provider = routingEngine.resolve(
                        auth.getMerchantId(), intent.getAmount(), intent.getCurrency(), null,
                        req.paymentMethodType() != null ? req.paymentMethodType() : "card");
                firstAccount = routingEngine.resolveAccount(auth.getMerchantId(), provider, auth.getMode())
                        .orElseThrow(() -> new IllegalStateException("No active connector for provider: " + provider));
                rawPmId = req.paymentMethodId();
            }

            // Build ordered candidate queue for the retry loop
            final List<ProviderAccount> accountQueue;
            if (req.paymentMethodId().startsWith("gw_tok_")) {
                accountQueue = List.of(firstAccount);
            } else {
                List<ProviderAccount> rest = routingEngine.resolveAccountsOrdered(
                        auth.getMerchantId(), provider, auth.getMode(), Set.of(firstAccount.getId()));
                accountQueue = new ArrayList<>(1 + rest.size());
                accountQueue.add(firstAccount);
                accountQueue.addAll(rest);
            }

            intent.setStatus(PaymentIntentStatus.PROCESSING);
            intent.setResolvedProvider(provider);
            intent.setConnectorAccountId(firstAccount.getId());
            paymentIntentRepository.save(intent);

            String pmType = req.paymentMethodType() != null ? req.paymentMethodType() : "card";
            return new Setup(intent, provider, accountQueue, rawPmId, pmType);
        });

        // --- Retry loop: each iteration is remote call + two short transactions ---
        ChargeResult lastResult = null;
        ProviderAccount usedAccount = null;
        int attemptCount = 0;

        for (ProviderAccount candidate : setup.accountQueue()) {
            if (attemptCount >= MAX_FAILOVER_ATTEMPTS) break;
            attemptCount++;
            usedAccount = candidate;

            // TX 2a: persist the attempt record before the remote call
            final ProviderAccount finalCandidate = candidate;
            UUID attemptId = txTemplate.execute(ts -> {
                PaymentRequest attempt = new PaymentRequest();
                attempt.setPaymentIntentId(setup.intent().getId());
                attempt.setAmount(setup.intent().getAmount());
                attempt.setCurrency(setup.intent().getCurrency());
                attempt.setPaymentMethodType(setup.pmType());
                attempt.setConnectorAccountId(finalCandidate.getId());
                return paymentRequestRepository.save(attempt).getId();
            });

            // Remote call — intentionally outside any transaction
            ProviderCredentials creds = providerAccountService.loadCredentials(candidate.getId());
            ChargeResult result = dispatcher.charge(setup.provider(), new ChargeRequest(
                    setup.intent().getId(), setup.intent().getAmount(), setup.intent().getCurrency(),
                    setup.pmType(), setup.rawPmId(),
                    "pi-" + setup.intent().getId() + "-" + attemptId
            ), creds);
            lastResult = result;

            // TX 2b: record outcome of this attempt
            final ChargeResult r = result;
            txTemplate.executeWithoutResult(ts -> {
                PaymentRequest attempt = paymentRequestRepository.findById(attemptId).orElseThrow();
                attempt.setStatus(r.success() ? PaymentRequestStatus.SUCCEEDED : PaymentRequestStatus.FAILED);
                attempt.setProviderRequestId(r.providerPaymentId());
                attempt.setProviderResponse(r.providerResponseJson());
                attempt.setFailureCode(r.failureCode());
                attempt.setFailureMessage(r.failureMessage());
                paymentRequestRepository.save(attempt);
            });

            if (result.success()) break;
            if (!failoverPolicy.isRetryable(result.failureCode())) break;
        }

        // --- TX 3: finalize intent status ---
        final ChargeResult finalResult = lastResult;
        final UUID usedAccountId = usedAccount != null ? usedAccount.getId() : null;

        PaymentIntentResponse response = txTemplate.execute(ts -> {
            PaymentIntent intent = paymentIntentRepository.findById(setup.intent().getId()).orElseThrow();
            if (finalResult != null && finalResult.success()) {
                intent.setStatus(PaymentIntentStatus.SUCCEEDED);
                intent.setConnectorAccountId(usedAccountId);
                intent.setProviderPaymentId(finalResult.providerPaymentId());
                intent.setProviderResponse(finalResult.providerResponseJson());
            } else {
                intent.setStatus(PaymentIntentStatus.FAILED);
            }
            PaymentIntent saved = paymentIntentRepository.save(intent);
            List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(saved.getId());
            return PaymentIntentResponse.from(saved, attempts, objectMapper);
        });

        // Publish event outside TX — async, best-effort
        String eventType = (finalResult != null && finalResult.success())
                ? "payment_intent.succeeded" : "payment_intent.failed";
        publishEvent(setup.intent().getMerchantId(), setup.intent().getId(), eventType, response);

        return response;
    }

    @Transactional
    public PaymentIntentResponse cancel(ApiKeyAuthentication auth, UUID intentId) {
        PaymentIntent intent = loadOwned(auth, intentId);

        if (intent.getStatus() != PaymentIntentStatus.REQUIRES_PAYMENT_METHOD
                && intent.getStatus() != PaymentIntentStatus.REQUIRES_CONFIRMATION) {
            throw new IllegalStateException(
                    "PaymentIntent cannot be canceled in status: " + intent.getStatus());
        }

        intent.setStatus(PaymentIntentStatus.CANCELED);
        PaymentIntentResponse response = toResponse(paymentIntentRepository.save(intent));
        publishEvent(intent.getMerchantId(), intent.getId(), "payment_intent.canceled", response);
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

    // TODO: Transactional Outbox — this publish is NOT atomic with the DB write above it.
    //   If the JVM crashes between save() and publishEvent(), the payment state is persisted
    //   but the webhook event is silently lost — the merchant's endpoint never gets notified.
    //
    //   This is an accepted trade-off: at the transaction volumes this gateway targets the
    //   crash window is extremely narrow, and the operational cost of a full outbox table
    //   (poller, deduplication, cleanup job) outweighs the risk.
    //
    //   If zero event loss becomes a requirement, the fix is:
    //     1. Add an `outbox_events` table (id, event_type, payload, published, created_at).
    //     2. Write the outbox row in the SAME @Transactional as the intent save — atomic.
    //     3. Replace publishEvent() here with outboxRepo.save(new OutboxEvent(...)).
    //     4. A @Scheduled poller reads unpublished rows, fires the Spring event, marks published.
    //   No Kafka/MQ needed — Postgres continues to be the durable queue.
    private void publishEvent(UUID merchantId, UUID intentId, String eventType, PaymentIntentResponse payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            eventPublisher.publishEvent(
                    new PaymentGatewayEvent(this, merchantId, eventType, intentId, json));
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
