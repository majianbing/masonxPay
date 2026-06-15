package com.masonx.paygateway.provider;

public record RefundResult(
        boolean success,
        String providerRefundId,
        String failureReason
) {}
