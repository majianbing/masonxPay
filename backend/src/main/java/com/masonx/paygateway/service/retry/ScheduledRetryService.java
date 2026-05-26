package com.masonx.paygateway.service.retry;

import com.masonx.paygateway.domain.retry.ScheduledRetryJob;
import com.masonx.paygateway.domain.retry.ScheduledRetryJobRepository;
import com.masonx.paygateway.domain.retry.ScheduledRetryOperation;
import com.masonx.paygateway.domain.retry.ScheduledRetryStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduledRetryService {

    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final List<ScheduledRetryStatus> ACTIVE_STATUSES = List.of(
            ScheduledRetryStatus.SCHEDULED,
            ScheduledRetryStatus.PROCESSING);

    private final ScheduledRetryJobRepository repository;
    private final Clock clock;

    @Autowired
    public ScheduledRetryService(ScheduledRetryJobRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ScheduledRetryService(ScheduledRetryJobRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ScheduledRetryJob> list(UUID merchantId, ScheduledRetryStatus status) {
        if (status == null) {
            return repository.findByMerchantIdOrderByCreatedAtDesc(merchantId);
        }
        return repository.findByMerchantIdAndStatusOrderByCreatedAtDesc(merchantId, status);
    }

    @Transactional
    public ScheduledRetryJob schedule(ScheduledRetryRequest request) {
        validate(request);
        ScheduledRetryJob existing = findExistingActive(request);
        if (existing != null) {
            return existing;
        }

        ScheduledRetryJob job = new ScheduledRetryJob();
        job.setMerchantId(request.merchantId());
        job.setOperation(request.operation());
        job.setStatus(ScheduledRetryStatus.SCHEDULED);
        job.setPaymentIntentId(request.paymentIntentId());
        job.setRefundId(request.refundId());
        job.setConnectorAccountId(request.connectorAccountId());
        job.setMaxAttempts(request.maxAttempts() > 0 ? request.maxAttempts() : DEFAULT_MAX_ATTEMPTS);
        job.setNextRunAt(request.nextRunAt() != null ? request.nextRunAt() : Instant.now(clock));
        job.setRetryReason(blankToNull(request.retryReason()));
        job.setLastErrorCode(blankToNull(request.lastErrorCode()));
        job.setLastErrorMessage(blankToNull(request.lastErrorMessage()));
        job.setPayloadJson(blankToNull(request.payloadJson()));
        return repository.save(job);
    }

    private ScheduledRetryJob findExistingActive(ScheduledRetryRequest request) {
        if (request.operation() == ScheduledRetryOperation.PAYMENT_CAPTURE) {
            return repository
                    .findFirstByMerchantIdAndOperationAndPaymentIntentIdAndStatusInOrderByCreatedAtDesc(
                            request.merchantId(), request.operation(), request.paymentIntentId(), ACTIVE_STATUSES)
                    .orElse(null);
        }
        if (request.operation() == ScheduledRetryOperation.REFUND) {
            return repository
                    .findFirstByMerchantIdAndOperationAndRefundIdAndStatusInOrderByCreatedAtDesc(
                            request.merchantId(), request.operation(), request.refundId(), ACTIVE_STATUSES)
                    .orElse(null);
        }
        return null;
    }

    @Transactional
    public ScheduledRetryJob cancel(UUID merchantId, UUID jobId) {
        ScheduledRetryJob job = repository.findByIdAndMerchantId(jobId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Scheduled retry job not found"));
        if (job.getStatus() != ScheduledRetryStatus.SCHEDULED) {
            throw new IllegalStateException("Only scheduled retry jobs can be canceled");
        }
        job.setStatus(ScheduledRetryStatus.CANCELED);
        job.setCompletedAt(Instant.now(clock));
        return repository.save(job);
    }

    @Transactional(readOnly = true)
    public List<ScheduledRetryJob> dueJobs(int limit) {
        int size = limit > 0 ? limit : 50;
        return repository.findDue(Instant.now(clock), PageRequest.of(0, size));
    }

    private void validate(ScheduledRetryRequest request) {
        if (request.merchantId() == null) {
            throw new IllegalArgumentException("merchantId is required");
        }
        if (request.operation() == null) {
            throw new IllegalArgumentException("operation is required");
        }
        if (request.operation() == ScheduledRetryOperation.PAYMENT_CAPTURE
                && request.paymentIntentId() == null) {
            throw new IllegalArgumentException("paymentIntentId is required for capture retries");
        }
        if (request.operation() == ScheduledRetryOperation.REFUND
                && request.refundId() == null) {
            throw new IllegalArgumentException("refundId is required for refund retries");
        }
        if (request.maxAttempts() < 0) {
            throw new IllegalArgumentException("maxAttempts cannot be negative");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
