package com.masonx.paygateway.domain.payment;

public enum PaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    PROCESSING,
    REQUIRES_CAPTURE,
    SUCCEEDED,
    FAILED,
    CANCELED
}
