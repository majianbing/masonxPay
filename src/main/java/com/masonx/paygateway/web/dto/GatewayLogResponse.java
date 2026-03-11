package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.log.GatewayLog;

import java.time.Instant;
import java.util.UUID;

public record GatewayLogResponse(
        UUID id,
        UUID merchantId,
        UUID apiKeyId,
        String requestId,
        String type,
        String method,
        String path,
        String requestHeaders,
        String requestBody,
        Integer responseStatus,
        String responseBody,
        Long durationMs,
        Instant createdAt
) {
    public static GatewayLogResponse from(GatewayLog log) {
        return new GatewayLogResponse(
                log.getId(),
                log.getMerchantId(),
                log.getApiKeyId(),
                log.getRequestId(),
                log.getType().name(),
                log.getMethod(),
                log.getPath(),
                log.getRequestHeaders(),
                log.getRequestBody(),
                log.getResponseStatus(),
                log.getResponseBody(),
                log.getDurationMs(),
                log.getCreatedAt()
        );
    }
}
