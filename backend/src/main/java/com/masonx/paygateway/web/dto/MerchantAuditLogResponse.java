package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.audit.MerchantAuditLog;

import java.time.Instant;
import java.util.UUID;

public record MerchantAuditLogResponse(
        UUID id,
        UUID merchantId,
        UUID actorUserId,
        String actorEmail,
        String action,
        String resourceType,
        String resourceId,
        String resourceLabel,
        String metadata,
        Instant createdAt
) {
    public static MerchantAuditLogResponse from(MerchantAuditLog log) {
        return new MerchantAuditLogResponse(
                log.getId(),
                log.getMerchantId(),
                log.getActorUserId(),
                log.getActorEmail(),
                log.getAction().name(),
                log.getResourceType(),
                log.getResourceId(),
                log.getResourceLabel(),
                log.getMetadata(),
                log.getCreatedAt()
        );
    }
}
