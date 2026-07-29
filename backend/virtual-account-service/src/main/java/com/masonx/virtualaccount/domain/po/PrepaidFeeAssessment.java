package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;

import java.time.Instant;

public record PrepaidFeeAssessment(
        String assessmentId,
        String merchantId,
        Mode mode,
        String eventType,
        String eventId,
        String programId,
        String cardId,
        String scheduleId,
        int scheduleVersion,
        String contextJson,
        String matchedRulesJson,
        String visibleTotalsJson,
        String hiddenTotalsJson,
        Instant createdAt
) {
}
