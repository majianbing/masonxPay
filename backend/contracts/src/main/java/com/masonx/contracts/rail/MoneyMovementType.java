package com.masonx.contracts.rail;

/** Protocol-independent classification of the money movement. */
public enum MoneyMovementType {
    // ISO 8583 card rail.
    // Online authorization: reserves cardholder funds/credit, but does not post settlement ledger movement.
    CARD_AUTH,

    // Reverses a prior authorization hold. Distinct from reversing a financial sale.
    CARD_AUTH_REVERSAL,

    // Gateway-facing capture/completion concept. ISO8583 implementations should map this deliberately
    // instead of treating it as a generic 0100 authorization.
    CARD_CAPTURE,

    // Financial purchase/sale, commonly a 0200-style flow where auth and financial presentment are combined.
    CARD_SALE,

    // Reverses a financial sale/voidable purchase. Distinct from CARD_AUTH_REVERSAL.
    CARD_SALE_REVERSAL,

    // Legacy generic reversal marker. Prefer CARD_AUTH_REVERSAL or CARD_SALE_REVERSAL for new card rail code.
    CARD_REVERSAL,

    // Merchant/cardholder refund after a prior settled transaction.
    CARD_REFUND,

    // Original credit / push-credit style movement. Not the same as refunding a known prior sale.
    CARD_CREDIT,

    // Clearing presentment from network/acquirer processing. Used for matching, not online authorization.
    CARD_CLEARING_PRESENTMENT,

    // Inter-participant settlement event. This is where ledger posting/reconciliation should happen.
    CARD_SETTLEMENT,

    // ISO 20022 bank rail
    BANK_CREDIT_TRANSFER,
    BANK_RETURN,
    BANK_STATUS_INQUIRY
}
