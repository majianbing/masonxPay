package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.dispute.DisputeEvidenceFile;

import java.time.Instant;
import java.util.UUID;

public record DisputeEvidenceFileResponse(
        UUID id,
        String externalId,
        UUID disputeId,
        String fileKey,
        String fileName,
        String contentType,
        Long sizeBytes,
        String url,
        Instant createdAt
) {
    public static DisputeEvidenceFileResponse from(DisputeEvidenceFile f, String url) {
        return new DisputeEvidenceFileResponse(
                f.getId(), f.getExternalId(), f.getDisputeId(), f.getFileKey(),
                f.getFileName(), f.getContentType(), f.getSizeBytes(),
                url, f.getCreatedAt());
    }
}
