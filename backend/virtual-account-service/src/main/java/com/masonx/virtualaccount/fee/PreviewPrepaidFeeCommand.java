package com.masonx.virtualaccount.fee;

import com.masonx.common.tenant.Mode;
import com.masonx.feeengine.FeeRule;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

public record PreviewPrepaidFeeCommand(
        String merchantId,
        Mode mode,
        String scheduleId,
        int version,
        String feeCurrency,
        int feeScale,
        RoundingMode roundingMode,
        List<FeeRule> rules,
        Map<String, String> metadata,
        Map<String, Object> context
) {
}
