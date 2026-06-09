package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.dispute.DisputeEvidenceFile;

import java.time.Instant;
import java.util.UUID;

public record DisputeEvidenceFileResponse(
        UUID id,
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
                f.getId(), f.getDisputeId(), f.getFileKey(),
                f.getFileName(), f.getContentType(), f.getSizeBytes(),
                url, f.getCreatedAt());
    }
}
