package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardholderKycStatus;
import com.masonx.virtualaccount.domain.constant.CardholderType;

import java.time.Instant;

/**
 * Minimal local reference to an issuer/card-program cardholder. Sensitive PII
 * belongs in a dedicated identity/KYC boundary, not the VA ledger service.
 */
public record Cardholder(
        String cardholderId,
        String merchantId,
        Mode mode,
        CardholderType type,
        String externalIssuerCardholderId,
        CardholderKycStatus kycStatus,
        String displayName,
        String displayRef,
        String profileRef,
        Instant createdAt,
        Instant updatedAt
) {
}
