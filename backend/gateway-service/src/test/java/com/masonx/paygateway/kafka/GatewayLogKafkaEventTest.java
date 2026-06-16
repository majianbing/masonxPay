package com.masonx.paygateway.kafka;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.log.GatewayLog;
import com.masonx.paygateway.domain.log.GatewayLogType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayLogKafkaEventTest {

    @Test
    void roundTrip_preservesGatewayLogFields() {
        GatewayLog log = new GatewayLog();
        UUID merchantId = UUID.randomUUID();
        UUID apiKeyId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-06-16T08:00:00Z");

        log.setMerchantId(merchantId);
        log.setApiKeyId(apiKeyId);
        log.setRequestId("req_123");
        log.setTraceId("trace_123");
        log.setType(GatewayLogType.API_REQUEST);
        log.setMethod("POST");
        log.setPath("/api/v1/payment-intents");
        log.setRequestHeaders("authorization: [REDACTED]");
        log.setRequestBody("{\"amount\":2500}");
        log.setResponseStatus(201);
        log.setResponseBody("{\"id\":\"pi_123\"}");
        log.setDurationMs(42L);
        log.setMode(ApiKeyMode.TEST);
        log.setCreatedAt(createdAt);

        GatewayLog restored = GatewayLogKafkaEvent.from(log).toGatewayLog();

        assertThat(restored.getMerchantId()).isEqualTo(merchantId);
        assertThat(restored.getApiKeyId()).isEqualTo(apiKeyId);
        assertThat(restored.getRequestId()).isEqualTo("req_123");
        assertThat(restored.getTraceId()).isEqualTo("trace_123");
        assertThat(restored.getType()).isEqualTo(GatewayLogType.API_REQUEST);
        assertThat(restored.getMethod()).isEqualTo("POST");
        assertThat(restored.getPath()).isEqualTo("/api/v1/payment-intents");
        assertThat(restored.getRequestHeaders()).isEqualTo("authorization: [REDACTED]");
        assertThat(restored.getRequestBody()).isEqualTo("{\"amount\":2500}");
        assertThat(restored.getResponseStatus()).isEqualTo(201);
        assertThat(restored.getResponseBody()).isEqualTo("{\"id\":\"pi_123\"}");
        assertThat(restored.getDurationMs()).isEqualTo(42L);
        assertThat(restored.getMode()).isEqualTo(ApiKeyMode.TEST);
        assertThat(restored.getCreatedAt()).isEqualTo(createdAt);
    }
}
