package com.masonx.paygateway.domain.payment;

public enum PaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    PROCESSING,
    REQUIRES_ACTION,    // 3DS / SCA challenge pending — waiting for customer authentication
    REQUIRES_CAPTURE,
    SUCCEEDED,
    FAILED,
    CANCELED
}
