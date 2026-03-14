package com.masonx.paygateway.web.dto;

public record PreviewPaymentResponse(
        boolean success,
        String status,              // "SUCCEEDED" | "FAILED"
        String provider,
        String connectorLabel,
        long amount,
        String currency,
        String providerPaymentId,   // Stripe pi_xxx if succeeded
        String failureCode,
        String failureMessage
) {}
