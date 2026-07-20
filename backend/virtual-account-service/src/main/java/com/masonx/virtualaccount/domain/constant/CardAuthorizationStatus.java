package com.masonx.virtualaccount.domain.constant;

/**
 * Lifecycle of a card authorization record.
 *
 * DECLINED is terminal from the start. AUTHORIZED moves to REVERSED (confirmed
 * reversal released the hold), SETTLED (clearing matched), or EXPIRED (hold
 * aged out unsettled). Only AUTHORIZED represents an open hold on the ledger.
 */
public enum CardAuthorizationStatus {
    AUTHORIZED,
    DECLINED,
    REVERSED,
    SETTLED,
    EXPIRED
}
