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
        String plainTextKey   // non-null only on creation; null on all subsequent reads
) {
    public static ApiKeyResponse from(ApiKey key, String plainTextKey) {
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
                plainTextKey
        );
    }
}
