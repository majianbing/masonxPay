package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardholderKycStatus;
import com.masonx.virtualaccount.domain.constant.CardholderType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCardholderRequest(
        @NotBlank String merchantId,
        Mode mode,
        @NotNull CardholderType type,
        @Size(max = 100) String externalIssuerCardholderId,
        CardholderKycStatus kycStatus,
        @Size(max = 120) String displayName,
        @Size(max = 120) String displayRef,
        @Size(max = 200) String profileRef
) {
}
