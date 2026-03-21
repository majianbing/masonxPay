package com.masonx.paygateway.provider;

public record ChargeResult(
        boolean success,
        String providerPaymentId,
        String providerResponseJson,
        String failureCode,
        String failureMessage,
        boolean retryable
) {}
