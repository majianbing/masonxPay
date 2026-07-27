package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerStatus;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerType;

import java.time.Instant;

public record IssuerPartnerResponse(
        String issuerPartnerId,
        String merchantId,
        Mode mode,
        String name,
        IssuerPartnerType adapterType,
        IssuerPartnerStatus status,
        String credentialsRef,
        String configJson,
        String webhookSecretRef,
        String externalProgramId,
        String externalFundingSourceId,
        Instant createdAt,
        Instant updatedAt
) {
}
