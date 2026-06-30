package com.masonx.rail.canonical;

import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Protocol-independent payment command. The {@link com.masonx.rail.router.RailRouter}
 * receives this, selects the appropriate {@link PaymentRailAdapter}, and hands it off.
 *
 * <p>Exactly one of {@code cardToken} or {@code debtorAccount}/{@code creditorAccount}
 * is non-null depending on the rail:
 * <ul>
 *   <li>CARD_ISO8583 — {@code cardToken} is set; bank account refs are null.
 *   <li>BANK_ISO20022 — {@code debtorAccount} and {@code creditorAccount} are set; cardToken is null.
 * </ul>
 */
public record CanonicalPaymentCommand(
        String paymentId,
        String merchantId,
        String mode,               // "TEST" or "LIVE"
        String idempotencyKey,
        PaymentRail rail,
        MoneyMovementType type,
        BigDecimal amount,
        String currency,
        CardToken cardToken,           // non-null for CARD_* movement types
        BankAccountRef debtorAccount,  // non-null for BANK_* movement types
        BankAccountRef creditorAccount,
        String originalPaymentId,      // non-null for reversals, refunds, returns
        Map<String, String> metadata
) {
}
