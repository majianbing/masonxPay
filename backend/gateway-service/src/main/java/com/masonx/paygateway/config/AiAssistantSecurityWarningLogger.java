package com.masonx.paygateway.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AiAssistantSecurityWarningLogger {

    private static final Logger log = LoggerFactory.getLogger(AiAssistantSecurityWarningLogger.class);

    private final AiAssistantProperties properties;

    public AiAssistantSecurityWarningLogger(AiAssistantProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void warnIfBootstrapSecurityIsActive() {
        if (!properties.enabled()) {
            return;
        }
        if (properties.hasAuthToken()) {
            log.info("RAG7: AI assistant gateway-to-AI bearer authentication is configured.");
            return;
        }
        log.warn(
                "RAG7 TODO: AI assistant is enabled without gateway-to-AI bearer authentication. "
                        + "Keep ai-service private and do not expose it directly until service authentication "
                        + "and production network controls are implemented."
        );
    }
}
