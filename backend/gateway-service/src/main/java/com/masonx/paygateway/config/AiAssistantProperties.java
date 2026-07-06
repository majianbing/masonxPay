package com.masonx.paygateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.assistant")
public record AiAssistantProperties(
        boolean enabled,
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs
) {
}
