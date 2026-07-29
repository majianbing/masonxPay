package com.masonx.virtualaccount.fee;

import com.masonx.common.tenant.Mode;
import com.masonx.feeengine.FeeRule;
import com.masonx.virtualaccount.domain.constant.PrepaidFeeScheduleStatus;

import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PublishPrepaidFeeScheduleVersionCommand(
        String scheduleId,
        String merchantId,
        Mode mode,
        int version,
        PrepaidFeeScheduleStatus status,
        String feeCurrency,
        int feeScale,
        RoundingMode roundingMode,
        Instant effectiveFrom,
        Instant effectiveTo,
        List<FeeRule> rules,
        Map<String, String> metadata
) {
}
