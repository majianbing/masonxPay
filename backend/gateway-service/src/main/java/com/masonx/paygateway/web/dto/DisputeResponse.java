package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.dispute.Dispute;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DisputeResponse(
        UUID id,
        String externalId,
        UUID merchantId,
        UUID paymentIntentId,
        String paymentIntentExternalId,
        String provider,
        String providerDisputeId,
        String status,
        String reason,
        long amount,
        String currency,
        Instant evidenceDueBy,
        Instant submittedAt,
        Instant resolvedAt,
        String mode,
        Instant createdAt,
        List<DisputeEvidenceFileResponse> files
) {
    public static DisputeResponse from(Dispute d, List<DisputeEvidenceFileResponse> files) {
        return from(d, files, null);
    }

    public static DisputeResponse from(Dispute d, List<DisputeEvidenceFileResponse> files, String paymentIntentExternalId) {
        return new DisputeResponse(
                d.getId(), d.getExternalId(), d.getMerchantId(), d.getPaymentIntentId(), paymentIntentExternalId,
                d.getProvider(),
                d.getProviderDisputeId(),
                d.getStatus() != null ? d.getStatus().name() : null,
                d.getReason() != null ? d.getReason().name() : null,
                d.getAmount(), d.getCurrency(),
                d.getEvidenceDueBy(), d.getSubmittedAt(), d.getResolvedAt(),
                d.getMode() != null ? d.getMode().name() : null,
                d.getCreatedAt(),
                files);
    }
}
