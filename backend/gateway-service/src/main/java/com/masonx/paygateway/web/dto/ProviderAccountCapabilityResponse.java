package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.connector.ProviderAccountCapability;

import java.time.Instant;
import java.util.UUID;

public record ProviderAccountCapabilityResponse(
        UUID id,
        UUID merchantId,
        UUID providerAccountId,
        String paymentMethodType,
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
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProviderAccountCapabilityResponse from(ProviderAccountCapability capability) {
        return new ProviderAccountCapabilityResponse(
                capability.getId(),
                capability.getMerchantId(),
                capability.getProviderAccountId(),
                capability.getPaymentMethodType(),
                capability.getCountry(),
                capability.getCurrency(),
                capability.getMinAmount(),
                capability.getMaxAmount(),
                capability.isSupportsManualCapture(),
                capability.isSupportsRefund(),
                capability.isSupportsPartialRefund(),
                capability.isSupports3ds(),
                capability.isSupportsRedirect(),
                capability.isSupportsProviderToken(),
                capability.isSupportsVaultToken(),
                capability.isSupportsNetworkToken(),
                capability.isSupportsInstallments(),
                capability.isEnabled(),
                capability.getCreatedAt(),
                capability.getUpdatedAt()
        );
    }
}
