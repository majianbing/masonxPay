package com.masonx.paygateway.domain.payment;

public enum PaymentAttemptType {
    PRIMARY,
    SAME_ACCOUNT_RETRY,
    FALLBACK,
    FALLBACK_RETRY
}
