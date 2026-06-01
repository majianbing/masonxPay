package com.masonx.paygateway.domain.retry;

public enum ScheduledRetryOperation {
    PAYMENT_CAPTURE,
    REFUND,
    INVOICE_PAYMENT
}
