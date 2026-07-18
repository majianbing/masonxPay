package com.masonx.virtualaccount.domain.constant;

public enum LedgerAccountType {
    // TENANT asset accounts
    CASH,
    WALLET,
    // TENANT liability accounts
    CREDIT_LINE,
    // TENANT receivable / reserve
    RECEIVABLE,
    RESERVE,
    // PLATFORM accounts
    FEE_INCOME,
    CLEARING,
    SUSPENSE,
    BAD_DEBT,

    // Phase MR — multi-rail infrastructure
    /** Ring-fenced wallet bound to a VirtualCard lifecycle (TENANT). NormalBalance: DEBIT. */
    PREPAID_CARD,
    /** Paired account holding authorized-but-unsettled prepaid card funds (TENANT). NormalBalance: DEBIT. */
    PREPAID_CARD_HOLD,
    /** Amounts owed by card network between sale and settlement batch (EXTERNAL). NormalBalance: DEBIT. */
    CARD_NETWORK_RECEIVABLE,
    /** Amounts owed from bank rail between pain.001 initiation and pacs.002 ACSC (EXTERNAL). NormalBalance: DEBIT. */
    BANK_RAIL_RECEIVABLE,
    /** Card transactions timed out — outcome unknown, reversal pending (PLATFORM). NormalBalance: DEBIT. */
    SUSPENSE_UNKNOWN_TXN
}
