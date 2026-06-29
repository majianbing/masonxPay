package com.masonx.rail.router;

import com.masonx.contracts.rail.PaymentRail;

import java.time.Instant;

public record RailRoutingDecision(
        String id,
        String paymentId,
        PaymentRail rail,
        String network,
        String adapterClass,
        Instant decidedAt
) {
}
