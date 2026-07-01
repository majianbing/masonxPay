package com.masonx.paygateway.service.retry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.payment.Refund;
import com.masonx.paygateway.domain.payment.RefundRepository;
import com.masonx.paygateway.domain.payment.RefundStatus;
import com.masonx.paygateway.domain.retry.ScheduledRetryJob;
import com.masonx.paygateway.domain.retry.ScheduledRetryJobRepository;
import com.masonx.paygateway.domain.retry.ScheduledRetryOperation;
import com.masonx.paygateway.domain.retry.ScheduledRetryStatus;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.RefundRequest;
import com.masonx.paygateway.provider.RefundResult;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.service.GatewayIdService;
import com.masonx.paygateway.service.ProviderAccountService;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import com.masonx.paygateway.web.dto.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduledRetryWorkerService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledRetryWorkerService.class);

    private final ScheduledRetryJobRepository retryJobRepository;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final RefundRepository refundRepository;
    private final PaymentProviderDispatcher dispatcher;
    private final ProviderAccountService providerAccountService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final PaymentMetrics metrics;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final String workerId;
    private final GatewayIdService gatewayIdService;

    @Value("${app.scheduled-retry.enabled:true}")
    private boolean enabled;

    @Value("${app.scheduled-retry.batch-size:25}")
    private int batchSize;

    @Value("${app.scheduled-retry.backoff-seconds:900}")
    private long backoffSeconds;

    @Autowired
    public ScheduledRetryWorkerService(ScheduledRetryJobRepository retryJobRepository,
                                       PaymentIntentRepository paymentIntentRepository,
                                       PaymentRequestRepository paymentRequestRepository,
                                       RefundRepository refundRepository,
                                       PaymentProviderDispatcher dispatcher,
                                       ProviderAccountService providerAccountService,
                                       OutboxEventRepository outboxEventRepository,
                                       ObjectMapper objectMapper,
                                       PaymentMetrics metrics,
                                       PlatformTransactionManager txManager,
                                       GatewayIdService gatewayIdService) {
        this(retryJobRepository, paymentIntentRepository, paymentRequestRepository, refundRepository, dispatcher,
                providerAccountService, outboxEventRepository, objectMapper, metrics, txManager,
                Clock.systemUTC(), defaultWorkerId(), gatewayIdService);
    }

    ScheduledRetryWorkerService(ScheduledRetryJobRepository retryJobRepository,
                                PaymentIntentRepository paymentIntentRepository,
                                PaymentRequestRepository paymentRequestRepository,
                                RefundRepository refundRepository,
                                PaymentProviderDispatcher dispatcher,
                                ProviderAccountService providerAccountService,
                                OutboxEventRepository outboxEventRepository,
                                ObjectMapper objectMapper,
                                PaymentMetrics metrics,
                                PlatformTransactionManager txManager,
                                Clock clock,
                                String workerId) {
        this(retryJobRepository, paymentIntentRepository, paymentRequestRepository, refundRepository, dispatcher,
                providerAccountService, outboxEventRepository, objectMapper, metrics, txManager, clock, workerId, null);
    }

    ScheduledRetryWorkerService(ScheduledRetryJobRepository retryJobRepository,
                                PaymentIntentRepository paymentIntentRepository,
                                PaymentRequestRepository paymentRequestRepository,
                                RefundRepository refundRepository,
                                PaymentProviderDispatcher dispatcher,
                                ProviderAccountService providerAccountService,
                                OutboxEventRepository outboxEventRepository,
                                ObjectMapper objectMapper,
                                PaymentMetrics metrics,
                                PlatformTransactionManager txManager,
                                Clock clock,
                                String workerId,
                                GatewayIdService gatewayIdService) {
        this.retryJobRepository = retryJobRepository;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.refundRepository = refundRepository;
        this.dispatcher = dispatcher;
        this.providerAccountService = providerAccountService;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.txTemplate = new TransactionTemplate(txManager);
        this.clock = clock;
        this.workerId = workerId;
        this.gatewayIdService = gatewayIdService;
    }

    @Scheduled(fixedDelayString = "${app.scheduled-retry.poll-delay-ms:60000}")
    public void processDueJobs() {
        if (!enabled) {
            return;
        }
        List<ScheduledRetryJob> due = retryJobRepository.findDue(
                Instant.now(clock), PageRequest.of(0, Math.max(1, batchSize)));
        for (ScheduledRetryJob job : due) {
            try {
                processJob(job.getId());
            } catch (Exception e) {
                log.warn("Scheduled retry job {} failed unexpectedly: {}", job.getId(), e.getMessage());
                failClaimedJob(job.getId(), "worker_error", e.getMessage());
            }
        }
    }

    void processJob(UUID jobId) {
        ScheduledRetryJob claimed = claim(jobId);
        if (claimed == null) {
            return;
        }

        RetryOutcome outcome = claimed.getOperation() == ScheduledRetryOperation.PAYMENT_CAPTURE
                ? executeCapture(claimed)
                : executeRefund(claimed);

        txTemplate.executeWithoutResult(ts -> applyOutcome(claimed.getId(), outcome));
    }

    private ScheduledRetryJob claim(UUID jobId) {
        return txTemplate.execute(ts -> {
            ScheduledRetryJob job = retryJobRepository.findScheduledForUpdate(jobId).orElse(null);
            if (job == null || job.getNextRunAt().isAfter(Instant.now(clock))) {
                return null;
            }
            if (job.getAttemptCount() >= job.getMaxAttempts()) {
                job.setStatus(ScheduledRetryStatus.FAILED);
                job.setCompletedAt(Instant.now(clock));
                retryJobRepository.save(job);
                return null;
            }
            job.setStatus(ScheduledRetryStatus.PROCESSING);
            job.setAttemptCount(job.getAttemptCount() + 1);
            job.setLockedAt(Instant.now(clock));
            job.setLockedBy(workerId);
            return retryJobRepository.save(job);
        });
    }

    private RetryOutcome executeCapture(ScheduledRetryJob job) {
        PaymentIntent intent = paymentIntentRepository.findByIdAndMerchantId(
                        job.getPaymentIntentId(), job.getMerchantId())
                .orElse(null);
        if (intent == null) {
            return RetryOutcome.terminalFailure("not_found", "Payment intent not found");
        }
        if (intent.getStatus() == PaymentIntentStatus.SUCCEEDED) {
            return RetryOutcome.ok();
        }
        if (intent.getStatus() != PaymentIntentStatus.REQUIRES_CAPTURE) {
            return RetryOutcome.terminalFailure("invalid_state",
                    "Payment intent is not waiting for capture");
        }
        if (intent.getConnectorAccountId() == null || intent.getProviderPaymentId() == null) {
            return RetryOutcome.terminalFailure("missing_provider_reference",
                    "Payment intent has no provider payment reference");
        }

        ProviderCredentials creds = providerAccountService.loadCredentials(intent.getConnectorAccountId());
        boolean captured = dispatcher.captureAtProvider(
                intent.getResolvedProvider(),
                intent.getProviderPaymentId(),
                creds);
        metrics.recordCaptureAttempted(
                intent.getResolvedProvider() != null ? intent.getResolvedProvider().name() : null,
                captured);
        return captured
                ? RetryOutcome.ok()
                : RetryOutcome.retryableFailure("capture_failed", "Provider capture retry failed");
    }

    private RetryOutcome executeRefund(ScheduledRetryJob job) {
        Refund refund = refundRepository.findById(job.getRefundId()).orElse(null);
        if (refund == null || !refund.getMerchantId().equals(job.getMerchantId())) {
            return RetryOutcome.terminalFailure("not_found", "Refund not found");
        }
        if (refund.getStatus() == RefundStatus.SUCCEEDED) {
            return RetryOutcome.ok();
        }
        if (refund.getStatus() != RefundStatus.PENDING && refund.getStatus() != RefundStatus.FAILED) {
            return RetryOutcome.terminalFailure("invalid_state", "Refund is not retryable");
        }

        PaymentIntent intent = paymentIntentRepository.findByIdAndMerchantId(
                        refund.getPaymentIntentId(), refund.getMerchantId())
                .orElse(null);
        if (intent == null || intent.getProviderPaymentId() == null) {
            return RetryOutcome.terminalFailure("missing_payment", "Payment intent is missing provider payment reference");
        }

        ProviderCredentials creds = intent.getConnectorAccountId() != null
                ? providerAccountService.loadCredentials(intent.getConnectorAccountId())
                : providerAccountService.resolveCredentials(
                refund.getMerchantId(), intent.getResolvedProvider(), refund.getMode());
        RefundResult result = dispatcher.refund(
                intent.getResolvedProvider(),
                new RefundRequest(refund.getId(), intent.getProviderPaymentId(),
                        refund.getAmount(), refund.getReason() != null ? refund.getReason().name() : null),
                creds);
        return result.success()
                ? RetryOutcome.refundSuccess(result.providerRefundId())
                : RetryOutcome.retryableFailure("refund_failed", result.failureReason());
    }

    private void applyOutcome(UUID jobId, RetryOutcome outcome) {
        ScheduledRetryJob job = retryJobRepository.findById(jobId).orElseThrow();
        if (outcome.succeeded()) {
            markOperationSucceeded(job, outcome);
            job.setStatus(ScheduledRetryStatus.SUCCEEDED);
            job.setCompletedAt(Instant.now(clock));
            job.setLastErrorCode(null);
            job.setLastErrorMessage(null);
        } else if (!outcome.retryable() || job.getAttemptCount() >= job.getMaxAttempts()) {
            markOperationFailed(job, outcome);
            job.setStatus(ScheduledRetryStatus.FAILED);
            job.setCompletedAt(Instant.now(clock));
            job.setLastErrorCode(outcome.errorCode());
            job.setLastErrorMessage(outcome.message());
        } else {
            job.setStatus(ScheduledRetryStatus.SCHEDULED);
            job.setNextRunAt(Instant.now(clock).plus(Duration.ofSeconds(Math.max(1, backoffSeconds))));
            job.setLastErrorCode(outcome.errorCode());
            job.setLastErrorMessage(outcome.message());
            job.setLockedAt(null);
            job.setLockedBy(null);
        }
        retryJobRepository.save(job);
    }

    private void markOperationSucceeded(ScheduledRetryJob job, RetryOutcome outcome) {
        if (job.getOperation() == ScheduledRetryOperation.PAYMENT_CAPTURE) {
            PaymentIntent intent = paymentIntentRepository.findByIdAndMerchantIdForUpdate(
                            job.getPaymentIntentId(), job.getMerchantId())
                    .orElse(null);
            if (intent != null && intent.getStatus() == PaymentIntentStatus.REQUIRES_CAPTURE) {
                intent.setStatus(PaymentIntentStatus.SUCCEEDED);
                intent = paymentIntentRepository.save(intent);
                List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
                writeOutboxEvent(intent.getMerchantId(), "payment_intent.succeeded", intent.getId(),
                        PaymentIntentResponse.from(intent, attempts, objectMapper, null));
            }
            return;
        }

        Refund refund = refundRepository.findByIdAndMerchantIdForUpdate(job.getRefundId(), job.getMerchantId())
                .orElse(null);
        if (refund != null && refund.getStatus() != RefundStatus.SUCCEEDED) {
            refund.setStatus(RefundStatus.SUCCEEDED);
            refund.setProviderRefundId(outcome.providerRefundId());
            refund.setFailureReason(null);
            refund = refundRepository.save(refund);
            writeOutboxEvent(refund.getMerchantId(), "refund.succeeded", refund.getId(),
                    RefundResponse.from(refund));
        }
    }

    private void markOperationFailed(ScheduledRetryJob job, RetryOutcome outcome) {
        if (job.getOperation() == ScheduledRetryOperation.PAYMENT_CAPTURE) {
            PaymentIntent intent = paymentIntentRepository.findByIdAndMerchantIdForUpdate(
                            job.getPaymentIntentId(), job.getMerchantId())
                    .orElse(null);
            if (intent != null && intent.getStatus() == PaymentIntentStatus.REQUIRES_CAPTURE) {
                intent.setStatus(PaymentIntentStatus.FAILED);
                intent = paymentIntentRepository.save(intent);
                List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
                writeOutboxEvent(intent.getMerchantId(), "payment_intent.failed", intent.getId(),
                        PaymentIntentResponse.from(intent, attempts, objectMapper, null));
            }
            return;
        }

        Refund refund = refundRepository.findByIdAndMerchantIdForUpdate(job.getRefundId(), job.getMerchantId())
                .orElse(null);
        if (refund != null && refund.getStatus() != RefundStatus.SUCCEEDED) {
            refund.setStatus(RefundStatus.FAILED);
            refund.setFailureReason(outcome.message());
            refund = refundRepository.save(refund);
            writeOutboxEvent(refund.getMerchantId(), "refund.failed", refund.getId(),
                    RefundResponse.from(refund));
        }
    }

    private void failClaimedJob(UUID jobId, String errorCode, String errorMessage) {
        txTemplate.executeWithoutResult(ts -> retryJobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == ScheduledRetryStatus.PROCESSING) {
                job.setStatus(ScheduledRetryStatus.SCHEDULED);
                job.setNextRunAt(Instant.now(clock).plus(Duration.ofSeconds(Math.max(1, backoffSeconds))));
                job.setLastErrorCode(errorCode);
                job.setLastErrorMessage(errorMessage);
                job.setLockedAt(null);
                job.setLockedBy(null);
                retryJobRepository.save(job);
            }
        }));
    }

    private void writeOutboxEvent(UUID merchantId, String eventType, UUID resourceId, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = new OutboxEvent(merchantId, eventType, resourceId, json);
            if (gatewayIdService != null) {
                gatewayIdService.assignOutboxEvent(event);
            }
            outboxEventRepository.save(event);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize outbox payload for scheduled retry event {} on {}: {}",
                    eventType, resourceId, e.getMessage());
        }
    }

    private static String defaultWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "scheduled-retry-worker";
        }
    }

    private record RetryOutcome(
            boolean succeeded,
            boolean retryable,
            String errorCode,
            String message,
            String providerRefundId
    ) {
        static RetryOutcome ok() {
            return new RetryOutcome(true, false, null, null, null);
        }

        static RetryOutcome refundSuccess(String providerRefundId) {
            return new RetryOutcome(true, false, null, null, providerRefundId);
        }

        static RetryOutcome retryableFailure(String errorCode, String message) {
            return new RetryOutcome(false, true, errorCode, message, null);
        }

        static RetryOutcome terminalFailure(String errorCode, String message) {
            return new RetryOutcome(false, false, errorCode, message, null);
        }
    }
}
