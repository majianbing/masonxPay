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
 * @param vccAccountId        Reserved for future use; currently null — VA handler resolves
 *                            the card account via {@code maskedPan}.
 * @param receivableAccountId Informational; VA handler resolves the receivable account
 *                            via {@code networkName}.
 * @param merchantId          Merchant owning this payment (v2). Required for bank transfer
 *                            journal posting (VA needs to find the merchant WALLET account).
 * @param maskedPan           Masked card PAN (v2); non-null for card payments. VA uses this
 *                            to look up the card account. Never contains raw PAN digits beyond
 *                            first-6 + last-4.
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
        Instant settledAt,
        // v2 additions — nullable on older producers
        String merchantId,
        String maskedPan
) {
    public static final String TYPE = "rail.settlement.recorded";
    public static final int SCHEMA_VERSION = 2;
}
