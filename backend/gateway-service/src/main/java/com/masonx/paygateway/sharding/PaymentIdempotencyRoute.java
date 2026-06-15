package com.masonx.paygateway.sharding;

import java.util.UUID;

public record PaymentIdempotencyRoute(
        UUID merchantId,
        String idempotencyKey,
        UUID paymentIntentId,
        int paymentShardId,
        IdempotencyReservationStatus status
) {
}
