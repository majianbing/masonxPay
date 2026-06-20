package com.masonx.virtualaccount.domain.constant;

public enum AccountRole {
    /** Owned by a merchant/org. Scoped by mode + org + merchant. */
    TENANT,
    /** Platform operator's own books (fees, clearing, suspense). No merchant owner. */
    PLATFORM,
    /** Mirror of an external party (provider, bank, chain). Carries provider_id. */
    EXTERNAL
}
