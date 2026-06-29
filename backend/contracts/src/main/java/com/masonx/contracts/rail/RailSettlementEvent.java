package com.masonx.contracts.rail;

import com.masonx.contracts.EventEnvelope;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Published by rail-service when a card sale or bank transfer reaches a terminal
 * settlement state. Consumed by virtual-account-service to post double-entry
 * ledger journals.
 *
 * <p>Additive-only contract — never remove, rename, or retype fields.
 * Bump SCHEMA_VERSION on every additive change.
 *
 * @param vccAccountId       Non-null for VCC card payments; identifies the PREPAID_CARD account to debit.
 * @param receivableAccountId The CARD_NETWORK_RECEIVABLE or BANK_RAIL_RECEIVABLE account to credit/close.
 */
public record RailSettlementEvent(
        EventEnvelope envelope,
        String railPaymentId,
        PaymentRail rail,
        MoneyMovementType movementType,
        String asset,
        BigDecimal amount,
        String vccAccountId,
        String receivableAccountId,
        String networkName,
        Instant settledAt
) {
    public static final String TYPE = "rail.settlement.recorded";
    public static final int SCHEMA_VERSION = 1;
}
