package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.retry.ScheduledRetryJob;
import com.masonx.paygateway.domain.retry.ScheduledRetryOperation;
import com.masonx.paygateway.domain.retry.ScheduledRetryStatus;

import java.time.Instant;
import java.util.UUID;

public record ScheduledRetryJobResponse(
        UUID id,
        UUID merchantId,
        ScheduledRetryOperation operation,
        ScheduledRetryStatus status,
        UUID paymentIntentId,
        UUID refundId,
        UUID connectorAccountId,
        int attemptCount,
        int maxAttempts,
        Instant nextRunAt,
        String lastErrorCode,
        String lastErrorMessage,
        String retryReason,
        Instant lockedAt,
        String lockedBy,
        Instant completedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static ScheduledRetryJobResponse from(ScheduledRetryJob job) {
        return new ScheduledRetryJobResponse(
                job.getId(),
                job.getMerchantId(),
                job.getOperation(),
                job.getStatus(),
                job.getPaymentIntentId(),
                job.getRefundId(),
                job.getConnectorAccountId(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getNextRunAt(),
                job.getLastErrorCode(),
                job.getLastErrorMessage(),
                job.getRetryReason(),
                job.getLockedAt(),
                job.getLockedBy(),
                job.getCompletedAt(),
                job.getCreatedAt(),
                job.getUpdatedAt()
        );
    }
}
