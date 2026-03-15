package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.connector.ProviderAccount;

import java.time.Instant;
import java.util.UUID;

public record ProviderAccountResponse(
        UUID id,
        UUID merchantId,
        String provider,
        String mode,
        String label,
        String credentialHint,    // last 4 chars of the primary secret — safe to display
        String clientKey,         // public key for the browser JS SDK (pk_xxx, applicationId, …)
        boolean primary,
        int weight,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProviderAccountResponse from(ProviderAccount a, String clientKey) {
        String hint = a.getSecretKeyHint() != null ? "...%s".formatted(a.getSecretKeyHint()) : null;
        return new ProviderAccountResponse(
                a.getId(),
                a.getMerchantId(),
                a.getProvider().name(),
                a.getMode().name(),
                a.getLabel(),
                hint,
                clientKey,
                a.isPrimary(),
                a.getWeight(),
                a.getStatus().name(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
