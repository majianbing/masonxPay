package com.masonx.contracts.settlement;

import com.masonx.contracts.EventEnvelope;

import java.util.UUID;

/**
 * Dedicated settlement domain event. Gateway publishes this (separate from the
 * external webhook-facing payment-status events) for the Virtual Account service
 * to consume and drive fee / clearing / settlement.
 *
 * <p><b>Money payload is intentionally not modeled here yet.</b> The amount
 * (minor units), asset/currency + scale, debit/credit direction, and fee
 * breakdown are owned by the VA domain design (see
 * {@code docs/engineering/virtual-account-guide.md}) and will be added as
 * additive fields. Only non-sensitive references appear here for now.
 */
public record SettlementEvent(
        EventEnvelope envelope,
        UUID paymentId,
        String providerRef
) {
    public static final String TYPE = "settlement.recorded";
    public static final int SCHEMA_VERSION = 1;
}
