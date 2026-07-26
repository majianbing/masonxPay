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
 * @param vccAccountId        Reserved for future use; currently null.
 * @param receivableAccountId Informational; VA handler resolves the receivable account
 *                            via {@code networkName}.
 * @param merchantId          Merchant owning this payment (v2). Required for bank transfer
 *                            journal posting (VA needs to find the merchant WALLET account).
 * @param maskedPan           Masked card PAN (v2); display/audit metadata only. Never contains
 *                            raw PAN digits beyond first-6 + last-4.
 * @param cardTokenId         Simulator card identity (v3); non-null for VA-issued card payments.
 *                            VA uses this to look up the card, never maskedPan.
 * @param issuerId            Issuer adapter identity (v4), e.g. RAIL_SIM; nullable on older events.
 * @param originalAuthorizationId Original issuer authorization id (v4), used to match clearing to holds.
 * @param originalRailPaymentId Original rail payment id (v4), used to match refunds/credits to prior clearing.
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
        String maskedPan,
        // v3 addition — nullable on older producers
        String cardTokenId,
        // v4 additions — nullable on older producers
        String issuerId,
        String originalAuthorizationId,
        String originalRailPaymentId
) {
    public static final String TYPE = "rail.settlement.recorded";
    public static final int SCHEMA_VERSION = 4;

    public RailSettlementEvent(
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
            String merchantId,
            String maskedPan,
            String cardTokenId
    ) {
        this(envelope, railPaymentId, rail, movementType, asset, amount, vccAccountId, receivableAccountId,
                networkName, settledAt, merchantId, maskedPan, cardTokenId, null, null, null);
    }
}
