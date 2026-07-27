package com.masonx.virtualaccount.issuer;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerType;

import java.time.LocalDate;

public record CreateIssuerCardCommand(
        String idempotencyKey,
        String merchantId,
        Mode mode,
        String programId,
        String issuerPartnerId,
        IssuerPartnerType issuerPartnerType,
        String cardholderId,
        String currency,
        LocalDate expiry
) {
}
