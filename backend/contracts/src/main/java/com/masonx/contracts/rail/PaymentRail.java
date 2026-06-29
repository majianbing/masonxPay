package com.masonx.contracts.rail;

/** Identifies which payment rail a transaction was routed to. */
public enum PaymentRail {
    CARD_ISO8583,
    BANK_ISO20022
}
