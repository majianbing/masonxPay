package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.routing.RoutePolicyAuditLog;

import java.time.Instant;
import java.util.UUID;

public record RoutePolicyAuditLogResponse(
        UUID id,
        UUID policyId,
        String action,
        UUID actorUserId,
        String beforeStatus,
        String afterStatus,
        String beforeState,
        String afterState,
        Instant createdAt
) {
    public static RoutePolicyAuditLogResponse from(RoutePolicyAuditLog log) {
        return new RoutePolicyAuditLogResponse(
                log.getId(),
                log.getPolicyId(),
                log.getAction(),
                log.getActorUserId(),
                log.getBeforeStatus(),
                log.getAfterStatus(),
                log.getBeforeState(),
                log.getAfterState(),
                log.getCreatedAt());
    }
}
