package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;

import java.math.BigDecimal;
import java.time.Instant;

public record PrepaidFeeAssessmentLine(
        Long lineId,
        String assessmentId,
        String merchantId,
        Mode mode,
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
