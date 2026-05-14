package com.masonx.paygateway.service;

public record PaymentRetryContext(boolean fallbackAllowed) {
    public static PaymentRetryContext sameAccountOnly() {
        return new PaymentRetryContext(false);
    }

    public static PaymentRetryContext allowFallback() {
        return new PaymentRetryContext(true);
    }
}
