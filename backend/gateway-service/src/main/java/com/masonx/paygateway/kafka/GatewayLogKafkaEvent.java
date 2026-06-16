package com.masonx.paygateway.kafka;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.log.GatewayLog;
import com.masonx.paygateway.domain.log.GatewayLogType;

import java.time.Instant;
import java.util.UUID;

public record GatewayLogKafkaEvent(
        UUID merchantId,
        UUID apiKeyId,
        String requestId,
        String traceId,
        GatewayLogType type,
        String method,
        String path,
        String requestHeaders,
        String requestBody,
        Integer responseStatus,
        String responseBody,
        Long durationMs,
        ApiKeyMode mode,
        Instant createdAt
) {

    public static GatewayLogKafkaEvent from(GatewayLog log) {
        return new GatewayLogKafkaEvent(
                log.getMerchantId(),
                log.getApiKeyId(),
                log.getRequestId(),
                log.getTraceId(),
                log.getType(),
                log.getMethod(),
                log.getPath(),
                log.getRequestHeaders(),
                log.getRequestBody(),
                log.getResponseStatus(),
                log.getResponseBody(),
                log.getDurationMs(),
                log.getMode(),
                log.getCreatedAt());
    }

    public GatewayLog toGatewayLog() {
        GatewayLog log = new GatewayLog();
        log.setMerchantId(merchantId);
        log.setApiKeyId(apiKeyId);
        log.setRequestId(requestId);
        log.setTraceId(traceId);
        log.setType(type);
        log.setMethod(method);
        log.setPath(path);
        log.setRequestHeaders(requestHeaders);
        log.setRequestBody(requestBody);
        log.setResponseStatus(responseStatus);
        log.setResponseBody(responseBody);
        log.setDurationMs(durationMs);
        log.setMode(mode);
        log.setCreatedAt(createdAt != null ? createdAt : Instant.now());
        return log;
    }
}
