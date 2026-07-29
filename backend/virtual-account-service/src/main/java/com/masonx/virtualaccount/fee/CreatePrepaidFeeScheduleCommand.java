package com.masonx.virtualaccount.fee;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.PrepaidFeeScheduleStatus;

public record CreatePrepaidFeeScheduleCommand(
        String merchantId,
        Mode mode,
        String programId,
        String bin,
        String channel,
        String name,
        PrepaidFeeScheduleStatus status
) {
}
