package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.PrepaidFeeScheduleStatus;

import java.time.Instant;

public record PrepaidFeeSchedule(
        String scheduleId,
        String merchantId,
        Mode mode,
        String programId,
        String bin,
        String channel,
        String name,
        PrepaidFeeScheduleStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
