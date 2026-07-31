package com.masonx.paygateway.fee;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record GatewayFeeAssessmentLine(
        Long lineId,
        String assessmentId,
        UUID merchantId,
        ApiKeyMode mode,
        String ruleId,
        int ruleVersion,
        String ruleName,
        String componentId,
        String name,
        String visibility,
        String currency,
        BigDecimal basisAmount,
        BigDecimal rawCalculatedAmount,
        BigDecimal amount,
        String roundingMode,
        int roundingScale,
        String metadataJson,
        Instant createdAt
) {
}
