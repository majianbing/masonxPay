package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.apikey.ApiKey;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.apikey.ApiKeyStatus;
import com.masonx.paygateway.domain.apikey.ApiKeyType;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyResponse(
        UUID id,
        UUID merchantId,
        ApiKeyMode mode,
        ApiKeyType type,
        String prefix,
        String name,
        ApiKeyStatus status,
        Instant lastUsedAt,
        Instant createdAt,
        Instant revokedAt,
        String plaintextKey,    // PUBLISHABLE: always present (safe public identifier)
                                // SECRET: non-null only at creation, null on all subsequent reads
        String secretPlaintext  // SECRET key raw value — non-null only at creation, then gone
) {
    public static ApiKeyResponse from(ApiKey key, String secretPlaintext) {
        return new ApiKeyResponse(
                key.getId(),
                key.getMerchantId(),
                key.getMode(),
                key.getType(),
                key.getPrefix(),
                key.getName(),
                key.getStatus(),
                key.getLastUsedAt(),
                key.getCreatedAt(),
                key.getRevokedAt(),
                key.getPlaintextKey(),   // always returned for pk, null for sk after creation
                secretPlaintext          // only non-null immediately after sk creation
        );
    }
}
