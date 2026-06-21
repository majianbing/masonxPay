package com.masonx.virtualaccount.domain.constant;

public enum AccountStatus {
    ACTIVE,
    /** Account is operationally frozen — no new entries permitted. */
    FROZEN,
    CLOSED
}
