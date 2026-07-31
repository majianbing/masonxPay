package com.masonx.paygateway.fee;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;

import java.time.Instant;
import java.util.UUID;

public record GatewayFeeScheduleVersion(
        String scheduleId,
        UUID merchantId,
        ApiKeyMode mode,
        int version,
        GatewayFeeScheduleStatus status,
        String feeCurrency,
        int feeScale,
        String roundingMode,
        Instant effectiveFrom,
        Instant effectiveTo,
        String rulesJson,
        String metadataJson,
        Instant createdAt,
        Instant publishedAt
) {
}
