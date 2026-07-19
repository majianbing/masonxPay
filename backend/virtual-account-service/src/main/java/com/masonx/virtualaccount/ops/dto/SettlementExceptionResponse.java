package com.masonx.virtualaccount.ops.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.masonx.virtualaccount.domain.po.SettlementException;

import java.time.Instant;

public record SettlementExceptionResponse(
        String exceptionId,
        String source,
        String eventId,
        String eventType,
        @JsonRawValue String payload,   // stored JSON, emitted as-is
        String reasonCode,
        String errorDetail,
        String status,
        int deliveryCount,
        int retryCount,
        String resolutionNote,
        Instant createdAt,
        Instant updatedAt
) {
    public static SettlementExceptionResponse from(SettlementException e) {
        return new SettlementExceptionResponse(
                e.exceptionId(),
                e.source().name(),
                e.eventId(),
                e.eventType(),
                e.payloadJson(),
                e.reasonCode().name(),
                e.errorDetail(),
                e.status().name(),
                e.deliveryCount(),
                e.retryCount(),
                e.resolutionNote(),
                e.createdAt(),
                e.updatedAt());
    }
}
