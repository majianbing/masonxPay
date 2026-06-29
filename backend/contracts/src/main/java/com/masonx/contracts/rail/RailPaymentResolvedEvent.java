package com.masonx.contracts.rail;

import com.masonx.contracts.EventEnvelope;

/**
 * Published by rail-service after a card auth that timed out (UNKNOWN) is definitively
 * settled by the reversal discipline:
 * <ul>
 *   <li>{@code outcome = "FAILED"} — reversal accepted (DE39=00); original auth voided.
 *   <li>{@code outcome = "SUCCEEDED"} — reserved for future same-day clearing confirmation flows.
 * </ul>
 *
 * <p>Gateway-service consumes this event to transition the corresponding
 * {@code PaymentIntent} out of PROCESSING into its terminal state.
 */
public record RailPaymentResolvedEvent(
        EventEnvelope envelope,
        String        railPaymentId,
        String        outcome         // "SUCCEEDED" | "FAILED"
) {
    public static final String TYPE           = "rail.payment.resolved";
    public static final int    SCHEMA_VERSION = 1;
}
