package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProviderAccountCapabilityRequest(
        @NotBlank String paymentMethodType,
        String country,
        String currency,
        Long minAmount,
        Long maxAmount,
        boolean supportsManualCapture,
        boolean supportsRefund,
        boolean supportsPartialRefund,
        boolean supports3ds,
        boolean supportsRedirect,
        boolean supportsProviderToken,
        boolean supportsVaultToken,
        boolean supportsNetworkToken,
        boolean supportsInstallments,
        boolean enabled
) {}
