package com.masonx.virtualaccount.domain.po;

import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionSource;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionStatus;

import java.time.Instant;

/**
 * One settlement event that arrived but could not post, parked with its full
 * payload for ops inspection and idempotent re-drive.
 */
public record SettlementException(
        String exceptionId,
        SettlementExceptionSource source,
        String eventId,
        String eventType,
        String payloadJson,
        SettlementExceptionReason reasonCode,
        String errorDetail,
        SettlementExceptionStatus status,
        int deliveryCount,
        int retryCount,
        String resolutionNote,
        Instant createdAt,
        Instant updatedAt
) {
}
