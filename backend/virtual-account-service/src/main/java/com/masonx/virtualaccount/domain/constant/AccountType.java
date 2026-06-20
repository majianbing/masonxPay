package com.masonx.virtualaccount.domain.constant;

public enum AccountType {
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
    BAD_DEBT
}
