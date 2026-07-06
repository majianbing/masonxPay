package com.masonx.paygateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.assistant")
public record AiAssistantProperties(
        boolean enabled,
        String baseUrl,
        int connectTimeoutMs,
        int readTimeoutMs,
        String authToken,
        int requestLimitPerWindow,
        int tokenLimitPerWindow,
        int budgetWindowSeconds
) {
    public boolean hasAuthToken() {
        return authToken != null && !authToken.isBlank();
    }
}
