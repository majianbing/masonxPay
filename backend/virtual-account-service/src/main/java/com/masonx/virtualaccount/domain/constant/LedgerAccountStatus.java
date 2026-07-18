package com.masonx.virtualaccount.domain.constant;

public enum LedgerAccountStatus {
    ACTIVE,
    /** Account is operationally frozen — no new entries permitted. */
    FROZEN,
    CLOSED
}
