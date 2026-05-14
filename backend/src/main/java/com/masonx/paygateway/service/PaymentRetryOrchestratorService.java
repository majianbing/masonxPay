package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.payment.PaymentAttemptType;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.payment.PaymentRequestStatus;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentRetryOrchestratorService {

    static final int HARD_MAX_ATTEMPTS = 5;

    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentProviderDispatcher dispatcher;
    private final ProviderAccountService providerAccountService;
    private final FailoverPolicy failoverPolicy;
    private final ConnectorCircuitBreaker circuitBreaker;
    private final PaymentMetrics metrics;
    private final TransactionTemplate txTemplate;
    private final int maxAttempts;
    private final int sameAccountMaxAttempts;

    public PaymentRetryOrchestratorService(PaymentRequestRepository paymentRequestRepository,
                                           PaymentProviderDispatcher dispatcher,
                                           ProviderAccountService providerAccountService,
                                           FailoverPolicy failoverPolicy,
                                           ConnectorCircuitBreaker circuitBreaker,
                                           PaymentMetrics metrics,
                                           PlatformTransactionManager txManager,
                                           @Value("${app.payments.retry.max-attempts:3}") int configuredMaxAttempts,
                                           @Value("${app.payments.retry.same-account-max-attempts:2}") int configuredSameAccountMaxAttempts) {
        this.paymentRequestRepository = paymentRequestRepository;
        this.dispatcher = dispatcher;
        this.providerAccountService = providerAccountService;
        this.failoverPolicy = failoverPolicy;
        this.circuitBreaker = circuitBreaker;
        this.metrics = metrics;
        this.txTemplate = new TransactionTemplate(txManager);
        this.maxAttempts = Math.max(1, Math.min(configuredMaxAttempts, HARD_MAX_ATTEMPTS));
        this.sameAccountMaxAttempts = Math.max(1, Math.min(configuredSameAccountMaxAttempts, HARD_MAX_ATTEMPTS));
    }

    /**
     * Executes an already-built route plan. This method deliberately has no @Transactional:
     * provider calls happen between short transaction blocks that only create/update attempts.
     */
    public Result execute(PaymentIntent intent, String rawPaymentMethodId, String paymentMethodType,
                          RoutePlan routePlan, PaymentRetryContext retryContext) {
        ChargeResult lastResult = null;
        RouteCandidate usedCandidate = null;
        RouteCandidate previousCandidate = null;
        Set<UUID> attemptedAccounts = new HashSet<>();
        int attempts = 0;
        int candidateIndex = 0;

        for (RouteCandidate candidate : routePlan.candidates()) {
            if (attempts >= maxAttempts) break;
            if (candidateIndex > 0 && !retryContext.fallbackAllowed()) break;
            if (!attemptedAccounts.add(candidate.accountId())) continue;

            if (previousCandidate != null) {
                metrics.recordFailover(previousCandidate.provider().name());
            }

            String providerIdempotencyKey = providerIdempotencyKey(intent, candidate);

            for (int sameAccountAttempt = 1;
                 sameAccountAttempt <= sameAccountMaxAttempts && attempts < maxAttempts;
                 sameAccountAttempt++) {
                attempts++;
                usedCandidate = candidate;

                PaymentAttemptType attemptType = attemptType(candidateIndex, sameAccountAttempt);
                UUID attemptId = createAttempt(intent, paymentMethodType, candidate.accountId(),
                        attempts, attemptType, providerIdempotencyKey);

                long chargeStart = System.currentTimeMillis();
                ChargeResult result;
                try {
                    ProviderCredentials creds = providerAccountService.loadCredentials(candidate.accountId());
                    result = dispatcher.charge(candidate.provider(), new ChargeRequest(
                            intent.getId(), intent.getAmount(), intent.getCurrency(),
                            paymentMethodType, rawPaymentMethodId,
                            providerIdempotencyKey,
                            intent.getBillingDetails(),
                            intent.getShippingDetails(),
                            intent.getCaptureMethod(),
                            null
                    ), creds);
                } catch (IllegalStateException e) {
                    result = failedAttempt("connector_not_configured", "Connector is not configured correctly");
                } catch (RuntimeException e) {
                    result = failedAttempt("provider_exception", "Provider execution failed");
                }
                metrics.recordChargeLatency(candidate.provider().name(), System.currentTimeMillis() - chargeStart);

                lastResult = result;
                updateAttempt(attemptId, result);

                if (result.success()) {
                    circuitBreaker.recordSuccess(candidate.accountId());
                    return new Result(lastResult, usedCandidate, attempts);
                }

                boolean retryable = failoverPolicy.isRetryable(result.failureCode());
                circuitBreaker.recordFailure(candidate.accountId(), retryable);
                if (!retryable) {
                    return new Result(lastResult, usedCandidate, attempts);
                }
            }
            previousCandidate = candidate;
            candidateIndex++;
        }

        return new Result(lastResult, usedCandidate, attempts);
    }

    private String providerIdempotencyKey(PaymentIntent intent, RouteCandidate candidate) {
        return "pi-" + intent.getId() + "-" + candidate.accountId();
    }

    private PaymentAttemptType attemptType(int candidateIndex, int sameAccountAttempt) {
        if (candidateIndex == 0 && sameAccountAttempt == 1) return PaymentAttemptType.PRIMARY;
        if (candidateIndex == 0) return PaymentAttemptType.SAME_ACCOUNT_RETRY;
        if (sameAccountAttempt == 1) return PaymentAttemptType.FALLBACK;
        return PaymentAttemptType.FALLBACK_RETRY;
    }

    private ChargeResult failedAttempt(String failureCode, String message) {
        return new ChargeResult(false, null, null, failureCode, message,
                true, false, null, null, null);
    }

    private UUID createAttempt(PaymentIntent intent, String paymentMethodType, UUID accountId,
                               int attemptNumber, PaymentAttemptType attemptType,
                               String providerIdempotencyKey) {
        return txTemplate.execute(ts -> {
            PaymentRequest attempt = new PaymentRequest();
            attempt.setPaymentIntentId(intent.getId());
            attempt.setAmount(intent.getAmount());
            attempt.setCurrency(intent.getCurrency());
            attempt.setPaymentMethodType(paymentMethodType);
            attempt.setConnectorAccountId(accountId);
            attempt.setAttemptNumber(attemptNumber);
            attempt.setAttemptType(attemptType);
            attempt.setProviderIdempotencyKey(providerIdempotencyKey);
            return paymentRequestRepository.save(attempt).getId();
        });
    }

    private void updateAttempt(UUID attemptId, ChargeResult result) {
        txTemplate.executeWithoutResult(ts -> {
            PaymentRequest attempt = paymentRequestRepository.findById(attemptId).orElseThrow();
            attempt.setStatus(result.success() ? PaymentRequestStatus.SUCCEEDED : PaymentRequestStatus.FAILED);
            attempt.setProviderRequestId(result.providerPaymentId());
            attempt.setProviderResponse(result.providerResponseJson());
            attempt.setFailureCode(result.failureCode());
            attempt.setFailureMessage(result.failureMessage());
            paymentRequestRepository.save(attempt);
        });
    }

    public record Result(ChargeResult lastResult, RouteCandidate usedCandidate, int attemptCount) {
        public boolean succeeded() {
            return lastResult != null && lastResult.success();
        }

        public String providerName() {
            return usedCandidate != null ? usedCandidate.provider().name() : null;
        }

        public UUID accountId() {
            return usedCandidate != null ? usedCandidate.accountId() : null;
        }
    }
}
