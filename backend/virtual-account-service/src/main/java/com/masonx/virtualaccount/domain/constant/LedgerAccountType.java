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
    /** Platform fee asset accrued from settlement fees before cash/revenue recognition. NormalBalance: DEBIT. */
    PLATFORM_FEE_RECEIVABLE,
    CLEARING,
    SUSPENSE,
    BAD_DEBT,

    // Phase MR — multi-rail infrastructure
    // Fund-holding tenant accounts use platform-books convention: merchant and
    // cardholder funds are platform LIABILITIES (CREDIT-normal), like WALLET.
    /** Ring-fenced funds bound to a VirtualCard lifecycle (TENANT). NormalBalance: CREDIT. */
    PREPAID_CARD,
    /** Paired account holding authorized-but-unsettled prepaid card funds (TENANT). NormalBalance: CREDIT. */
    PREPAID_CARD_HOLD,
    /** Card network settlement account — issuing side: platform owes the network on card use
     *  (payable semantics; name retained until CoA cleanup) (EXTERNAL). NormalBalance: CREDIT. */
    CARD_NETWORK_RECEIVABLE,
    /** Money sitting at the bank rail owed to the platform (EXTERNAL). NormalBalance: DEBIT. */
    BANK_RAIL_RECEIVABLE,
    /** Card transactions timed out — outcome unknown, reversal pending (PLATFORM). NormalBalance: DEBIT. */
    SUSPENSE_UNKNOWN_TXN,

    // Fix #3 — merchant debt
    /** Money a merchant owes the platform: bank-return shortfall booked as platform asset,
     *  recouped from later inbound settlements (TENANT). NormalBalance: DEBIT. */
    MERCHANT_RECEIVABLE
}
