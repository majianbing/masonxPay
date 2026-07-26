package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;

import java.math.BigDecimal;
import java.time.Instant;

public record CardClearingEvent(
        String clearingEventId,
        String eventId,
        String railPaymentId,
        String originalRailPaymentId,
        String issuerId,
        String originalAuthorizationId,
        String movementType,
        String cardId,
        String authId,
        String merchantId,
        Mode mode,
        BigDecimal amount,
        String currency,
        String status,
        Instant createdAt
) {
}
