package com.masonx.rail.canonical;

import java.time.Instant;

/**
 * Protocol-independent response returned by a {@link PaymentRailAdapter}.
 * Adapter implementations translate their protocol-specific response (ISO 8583
 * DE39, ISO 20022 status codes) into this canonical form.
 */
public record RailResponse(
        String railPaymentId,
        RailPaymentStatus status,
        String authCode,       // DE38 for card auth; null for bank rail
        String responseCode,   // DE39 for ISO 8583; GroupStatus/TxStatus for ISO 20022
        String networkRef,     // correlation key (ISO 8583) or EndToEndId (ISO 20022)
        String failureReason,  // human-readable reason for DECLINED / FAILED
        Instant respondedAt
) {
}
