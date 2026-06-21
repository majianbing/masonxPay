package com.masonx.virtualaccount.domain.constant;

public enum NormalBalance {
    /** Balance increases on DEBIT (asset accounts: CASH, WALLET, RECEIVABLE, RESERVE). */
    DEBIT,
    /** Balance increases on CREDIT (liability accounts: CREDIT_LINE). */
    CREDIT
}
