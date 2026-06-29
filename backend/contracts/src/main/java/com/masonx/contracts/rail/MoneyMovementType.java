package com.masonx.contracts.rail;

/** Protocol-independent classification of the money movement. */
public enum MoneyMovementType {
    // ISO 8583 card rail
    CARD_AUTH,
    CARD_CAPTURE,
    CARD_SALE,
    CARD_REVERSAL,
    CARD_REFUND,
    CARD_CREDIT,

    // ISO 20022 bank rail
    BANK_CREDIT_TRANSFER,
    BANK_RETURN,
    BANK_STATUS_INQUIRY
}
