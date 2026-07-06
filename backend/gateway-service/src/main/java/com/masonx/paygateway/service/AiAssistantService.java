package com.masonx.paygateway.service;

import com.masonx.paygateway.config.AiAssistantProperties;
import com.masonx.paygateway.domain.audit.AuditAction;
import com.masonx.paygateway.web.dto.RagAnswerRequest;
import com.masonx.paygateway.web.dto.RagAnswerResponse;
import com.masonx.paygateway.web.dto.RagIndexStatusResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiAssistantService {

    // This controller lives in the merchant realm, so callers are only ever entitled to
    // merchant-audience documentation. The audience is resolved here, never taken from the
    // client, so a merchant user cannot request platform-admin/operator/engineering content.
    private static final String MERCHANT_AUDIENCE = "merchant";

    private final AiAssistantProperties properties;
    private final RestTemplate restTemplate;
    private final AiAssistantBudgetService budgetService;
    private final MerchantAuditLogService auditLogService;

    public AiAssistantService(AiAssistantProperties properties,
                              @Qualifier("aiAssistantRestTemplate") RestTemplate restTemplate,
                              AiAssistantBudgetService budgetService,
                              MerchantAuditLogService auditLogService) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.budgetService = budgetService;
        this.auditLogService = auditLogService;
    }

    public RagAnswerResponse answer(UUID merchantId, RagAnswerRequest request) {
        if (!properties.enabled()) {
            RagAnswerResponse response = new RagAnswerResponse(
                    "The MasonXPay assistant is disabled in this environment.",
                    List.of(),
                    "assistant_disabled",
                    "none",
                    List.of(),
                    "gateway-fallback",
                    "gateway-fallback",
                    "none",
                    "none"
            );
            auditQuestion(merchantId, request, response, null);
            return response;
        }
        AiAssistantBudgetService.BudgetResult budget = budgetService.reserve(merchantId, request.question());
        if (!budget.accepted()) {
            RagAnswerResponse response = new RagAnswerResponse(
                    "The MasonXPay assistant budget for this merchant has been reached. Try again after the current budget window resets.",
                    List.of(),
                    budget.rejectionReason(),
                    "none",
                    List.of(),
                    "gateway-fallback",
                    "gateway-fallback",
                    "none",
                    "none"
            );
            auditQuestion(merchantId, request, response, budget);
            return response;
        }

        AiServiceRequest aiRequest = new AiServiceRequest(
                request.question(),
                MERCHANT_AUDIENCE,
                request.maxCitations() != null ? request.maxCitations() : 4,
                request.correlationId(),
                merchantId.toString()
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        applyAiServiceAuth(headers);
        try {
            var response = restTemplate.postForEntity(
                    properties.baseUrl() + "/v1/rag/answer",
                    new HttpEntity<>(aiRequest, headers),
                    RagAnswerResponse.class
            );
            HttpStatusCode status = response.getStatusCode();
            if (!status.is2xxSuccessful() || response.getBody() == null) {
                RagAnswerResponse unavailable = unavailableAnswer();
                auditQuestion(merchantId, request, unavailable, budget);
                return unavailable;
            }
            auditQuestion(merchantId, request, response.getBody(), budget);
            return response.getBody();
        } catch (RestClientException ex) {
            RagAnswerResponse unavailable = unavailableAnswer();
            auditQuestion(merchantId, request, unavailable, budget);
            return unavailable;
        }
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
                    "gateway-fallback",
                    "gateway-fallback",
                    "none",
                    "none",
                    List.of()
            );
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            applyAiServiceAuth(headers);
            var response = restTemplate.exchange(
                    properties.baseUrl() + "/v1/rag/status",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    RagIndexStatusResponse.class
            );
            HttpStatusCode status = response.getStatusCode();
            if (!status.is2xxSuccessful() || response.getBody() == null) {
                return unavailableStatus();
            }
            return response.getBody();
        } catch (RestClientException ex) {
            return unavailableStatus();
        }
    }

    private RagAnswerResponse unavailableAnswer() {
        return new RagAnswerResponse(
                "The MasonXPay assistant is temporarily unavailable. No payment or configuration changes were attempted.",
                List.of(),
                "assistant_unavailable",
                "none",
                List.of(),
                "gateway-fallback",
                "gateway-fallback",
                "none",
                "none"
        );
    }

    private RagIndexStatusResponse unavailableStatus() {
        return new RagIndexStatusResponse(
                "unavailable",
                "unknown",
                "unknown",
                0,
                0,
                "unknown",
                "gateway-fallback",
                "gateway-fallback",
                "none",
                "none",
                List.of()
        );
    }

    private void auditQuestion(UUID merchantId,
                               RagAnswerRequest request,
                               RagAnswerResponse response,
                               AiAssistantBudgetService.BudgetResult budget) {
        auditLogService.record(merchantId, AuditAction.ASSISTANT_QUESTION_ASKED,
                "ASSISTANT", request.correlationId(), "RAG assistant question",
                Map.ofEntries(
                        Map.entry("audience", MERCHANT_AUDIENCE),
                        Map.entry("correlationId", request.correlationId() != null ? request.correlationId() : ""),
                        Map.entry("maxCitations", request.maxCitations() != null ? request.maxCitations() : 4),
                        Map.entry("refusalReason", response.refusalReason() != null ? response.refusalReason() : ""),
                        Map.entry("confidence", response.confidence()),
                        Map.entry("citationCount", response.citations() != null ? response.citations().size() : 0),
                        Map.entry("promptTemplateVersion", response.promptTemplateVersion() != null ? response.promptTemplateVersion() : ""),
                        Map.entry("answerPolicyVersion", response.answerPolicyVersion() != null ? response.answerPolicyVersion() : ""),
                        Map.entry("modelProvider", response.modelProvider() != null ? response.modelProvider() : ""),
                        Map.entry("modelName", response.modelName() != null ? response.modelName() : ""),
                        Map.entry("estimatedTokens", budget != null ? budget.estimatedTokens() : 0),
                        Map.entry("requestsRemaining", budget != null ? budget.requestsRemaining() : -1),
                        Map.entry("tokensRemaining", budget != null ? budget.tokensRemaining() : -1)
                ));
    }

    private void applyAiServiceAuth(HttpHeaders headers) {
        if (properties.hasAuthToken()) {
            headers.setBearerAuth(properties.authToken());
        }
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
