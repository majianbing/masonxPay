package com.masonx.virtualaccount.fee;

import com.masonx.common.tenant.Mode;

import java.time.Instant;
import java.util.Map;

public record AssessPrepaidFeeCommand(
        String merchantId,
        Mode mode,
        String eventType,
        String eventId,
        String programId,
        String cardId,
        String bin,
        String channel,
        Map<String, Object> context,
        Instant occurredAt
) {
}
