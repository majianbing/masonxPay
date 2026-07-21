package com.masonx.virtualaccount.domain.constant;

/**
 * Why a settlement event could not post. Reasons are permanent/business
 * failures — transient infrastructure errors are retried by the Kafka error
 * handler and only land here (as UNEXPECTED_ERROR) once retries are exhausted.
 */
public enum SettlementExceptionReason {
    MISSING_EVENT_FIELD,          // event lacks a required field (cardTokenId, merchantId, ...)
    CARD_NOT_FOUND,               // no active card matches the event's card reference
    WALLET_ACCOUNT_NOT_FOUND,     // merchant WALLET account missing for mode/asset
    RECEIVABLE_ACCOUNT_NOT_FOUND, // EXTERNAL receivable account missing for network/asset
    LEDGER_ACCOUNT_NOT_FOUND,     // posting-time account lookup failed (VA_ACCOUNT_NOT_FOUND/NOT_ACTIVE)
    INVALID_AMOUNT,               // non-positive amount
    INSUFFICIENT_BALANCE,         // posting would drive a balance negative (e.g. bank return after spend)
    POSTING_FAILED,               // other business rule rejected the journal
    UNEXPECTED_ERROR,             // parked by the Kafka backstop after retries exhausted
    MOVEMENT_TYPE_NOT_IMPLEMENTED // movement type is a defined MoneyMovementType with no posting rule yet
}
