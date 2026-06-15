package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record SimulateRouteRequest(
        String mode,
        @NotNull Long amount,
        @NotBlank String currency,
        String country,
        String paymentMethodType,
        String captureMethod,
        UUID customerId,
        String orderId,
        Map<String, String> metadata,
        UUID instrumentId,
        String instrumentSource,
        String instrumentPortability,
        String cardBrand,
        String binCountry,
        String issuerCountry,
        String cardType,
        String walletType
) {
}
