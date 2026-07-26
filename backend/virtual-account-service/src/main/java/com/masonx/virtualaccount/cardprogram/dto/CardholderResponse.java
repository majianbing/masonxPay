package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardholderKycStatus;
import com.masonx.virtualaccount.domain.constant.CardholderType;

import java.time.Instant;

public record CardholderResponse(
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
