package com.masonx.paygateway.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate webhookRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .errorHandler(new NoOpResponseErrorHandler())
                .build();
    }

    @Bean
    public RestTemplate aiAssistantRestTemplate(RestTemplateBuilder builder, AiAssistantProperties properties) {
        return builder
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.readTimeoutMs()))
                .errorHandler(new NoOpResponseErrorHandler())
                .build();
    }

    @Bean
    public RestTemplate virtualAccountRestTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .errorHandler(new NoOpResponseErrorHandler())
                .build();
    }

    private static class NoOpResponseErrorHandler implements ResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
            // Intentionally empty — we inspect the status code ourselves
        }
    }
}
