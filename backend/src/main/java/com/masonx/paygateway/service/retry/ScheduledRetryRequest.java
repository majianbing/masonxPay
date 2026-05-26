package com.masonx.paygateway.service.retry;

import com.masonx.paygateway.domain.retry.ScheduledRetryOperation;

import java.time.Instant;
import java.util.UUID;

public record ScheduledRetryRequest(
        UUID merchantId,
        ScheduledRetryOperation operation,
        UUID paymentIntentId,
        UUID refundId,
        UUID connectorAccountId,
        int maxAttempts,
        Instant nextRunAt,
        String retryReason,
        String lastErrorCode,
        String lastErrorMessage,
        String payloadJson
) {
}
