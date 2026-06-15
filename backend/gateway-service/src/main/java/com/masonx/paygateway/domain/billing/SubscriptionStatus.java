package com.masonx.paygateway.domain.billing;

public enum SubscriptionStatus {
    INCOMPLETE,
    TRIALING,
    ACTIVE,
    PAST_DUE,
    CANCELED,
    UNPAID
}
