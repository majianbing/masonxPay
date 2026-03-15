package com.masonx.paygateway.web.dto;

/**
 * Returned when creating a key pair (sk + pk created together).
 * secretKey.secretPlaintext is non-null only here — never retrievable again.
 * publishableKey.plaintextKey is always available on subsequent reads.
 */
public record ApiKeyPairResponse(
        ApiKeyResponse secretKey,
        ApiKeyResponse publishableKey
) {}
