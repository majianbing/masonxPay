package com.masonx.contracts.settlement;

import com.masonx.contracts.EventEnvelope;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dedicated settlement domain event. Gateway publishes this (separate from the
 * external webhook-facing payment-status events) for the Virtual Account service
 * to consume and drive fee / clearing / settlement.
 *
 * <p>Schema v2 adds money fields. All new fields are nullable for backward
 * compatibility — older producers that omit them produce null values; the VA
 * consumer must apply safe defaults (see SettlementEventMapper).
 *
 * <p>Additive-only contract: never remove, rename, or retype fields. Bump
 * SCHEMA_VERSION on every additive change.
 *
 * @param amount     Gross settlement amount (e.g. 100.00 for $100). Never negative.
 * @param feeAmount  Platform fee withheld from the gross amount (e.g. 2.00).
 *                   Null means no fee; treated as zero by the consumer.
 * @param asset      Asset code: "USD", "BTC", "USDC", etc.
 * @param assetClass "FIAT" or "CRYPTO" — drives precision rules in VA.
 * @param scale      Decimal precision: 2 for fiat, 8 for crypto.
 * @param direction  "CREDIT" (money in for merchant) or "DEBIT" (money out, e.g. refund reversal).
 */
public record SettlementEvent(
        EventEnvelope envelope,
        UUID paymentId,
        String providerRef,

        // --- v2: money payload ---
        BigDecimal amount,
        BigDecimal feeAmount,
        String asset,
        String assetClass,
        Integer scale,
        String direction
) {
    public static final String TYPE = "settlement.recorded";
    public static final int SCHEMA_VERSION = 2;
}
