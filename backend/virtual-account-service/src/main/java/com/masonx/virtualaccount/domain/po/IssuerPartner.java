package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerStatus;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerType;

import java.time.Instant;

/**
 * Merchant/mode-scoped issuer bank or issuer processor adapter configuration.
 * Secret fields are references only; raw credentials and webhook secrets live
 * outside VA domain tables.
 */
public record IssuerPartner(
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
