package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerStatus;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateIssuerPartnerRequest(
        @NotBlank String merchantId,
        Mode mode,
        @NotBlank @Size(max = 120) String name,
        @NotNull IssuerPartnerType adapterType,
        IssuerPartnerStatus status,
        @Size(max = 200) String credentialsRef,
        String configJson,
        @Size(max = 200) String webhookSecretRef,
        @Size(max = 100) String externalProgramId,
        @Size(max = 100) String externalFundingSourceId
) {
}
