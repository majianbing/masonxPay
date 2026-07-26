package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;

import java.time.Instant;

public record CardControlProfile(
        String cardId,
        String merchantId,
        Mode mode,
        String controlsJson,
        Instant createdAt,
        Instant updatedAt
) {
}
