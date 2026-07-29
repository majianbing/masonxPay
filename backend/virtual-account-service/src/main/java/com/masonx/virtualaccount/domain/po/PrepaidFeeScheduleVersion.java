package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.PrepaidFeeScheduleStatus;

import java.time.Instant;

public record PrepaidFeeScheduleVersion(
        String scheduleId,
        String merchantId,
        Mode mode,
        int version,
        PrepaidFeeScheduleStatus status,
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
