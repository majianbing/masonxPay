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
        String secretKeyHint,        // e.g. "...x4z9" — last 4 chars only
        boolean hasPublishableKey,
        boolean primary,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProviderAccountResponse from(ProviderAccount a) {
        return new ProviderAccountResponse(
                a.getId(),
                a.getMerchantId(),
                a.getProvider().name(),
                a.getMode().name(),
                a.getLabel(),
                "...%s".formatted(a.getSecretKeyHint()),
                a.getEncryptedPublishableKey() != null,
                a.isPrimary(),
                a.getStatus().name(),
                a.getCreatedAt(),
                a.getUpdatedAt()
        );
    }
}
