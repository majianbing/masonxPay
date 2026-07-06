package com.masonx.paygateway.service;

import com.masonx.paygateway.config.AiAssistantProperties;
import com.masonx.paygateway.web.dto.RagAnswerRequest;
import com.masonx.paygateway.web.dto.RagAnswerResponse;
import com.masonx.paygateway.web.dto.RagIndexStatusResponse;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class AiAssistantService {

    private final AiAssistantProperties properties;
    private final RestTemplate restTemplate;

    public AiAssistantService(AiAssistantProperties properties, RestTemplate restTemplate) {
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    public RagAnswerResponse answer(UUID merchantId, RagAnswerRequest request) {
        if (!properties.enabled()) {
            return new RagAnswerResponse(
                    "The MasonXPay assistant is disabled in this environment.",
                    List.of(),
                    "assistant_disabled",
                    "none",
                    List.of()
            );
        }
        if (!isSupportedAudience(request.audience())) {
            throw new IllegalArgumentException("Invalid assistant audience: " + request.audience());
        }

        AiServiceRequest aiRequest = new AiServiceRequest(
                request.question(),
                request.audience(),
                request.maxCitations() != null ? request.maxCitations() : 4,
                request.correlationId(),
                merchantId.toString()
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        var response = restTemplate.postForEntity(
                properties.baseUrl() + "/v1/rag/answer",
                new HttpEntity<>(aiRequest, headers),
                RagAnswerResponse.class
        );
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("AI assistant service is unavailable");
        }
        return response.getBody();
    }

    public RagIndexStatusResponse status(UUID merchantId) {
        if (!properties.enabled()) {
            return new RagIndexStatusResponse(
                    "disabled",
                    "unknown",
                    "unknown",
                    0,
                    0,
                    "none",
                    List.of()
            );
        }
        var response = restTemplate.getForEntity(
                properties.baseUrl() + "/v1/rag/status",
                RagIndexStatusResponse.class
        );
        HttpStatusCode status = response.getStatusCode();
        if (!status.is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("AI assistant service is unavailable");
        }
        return response.getBody();
    }

    private boolean isSupportedAudience(String audience) {
        return audience != null
                && (audience.equals("merchant")
                || audience.equals("developer")
                || audience.equals("platform-admin")
                || audience.equals("operator"));
    }

    private record AiServiceRequest(
            String question,
            String audience,
            int maxCitations,
            String correlationId,
            String merchantId
    ) {
    }
}
