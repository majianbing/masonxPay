package com.masonx.paygateway.service;

import com.masonx.paygateway.config.AiAssistantProperties;
import com.masonx.paygateway.domain.audit.AuditAction;
import com.masonx.paygateway.web.dto.RagAnswerRequest;
import com.masonx.paygateway.web.dto.RagAnswerResponse;
import com.masonx.paygateway.web.dto.RagIndexStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAssistantServiceTest {

    @Test
    void answer_whenDisabled_refusesWithoutCallingAiService() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MerchantAuditLogService auditLogService = mock(MerchantAuditLogService.class);
        AiAssistantService service = service(false, restTemplate, properties(false), auditLogService);

        RagAnswerResponse response = service.answer(UUID.randomUUID(),
                new RagAnswerRequest("How do I configure a connector?", 4, null));

        assertThat(response.refusalReason()).isEqualTo("assistant_disabled");
        verify(auditLogService).record(any(), eq(AuditAction.ASSISTANT_QUESTION_ASKED),
                eq("ASSISTANT"), isNull(), eq("RAG assistant question"), anyMap());
        server.verify();
    }

    @Test
    void answer_forwardsDocsOnlyQuestionToAiService() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MerchantAuditLogService auditLogService = mock(MerchantAuditLogService.class);
        AiAssistantService service = service(true, restTemplate, properties(true), auditLogService);

        server.expect(once(), requestTo("http://ai-service:8090/v1/rag/answer"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.audience").value("merchant"))
                .andRespond(withSuccess("""
                        {
                          "answer":"Use TEST connector settings.",
                          "citations":[{"sourcePath":"docs/engineering/connector-development.md","headingPath":"Connectors","sourceType":"engineering","stability":"stable"}],
                          "refusalReason":null,
                          "confidence":"medium",
                          "retrievedChunks":[],
                          "promptTemplateVersion":"rag-answer-template-v1",
                          "answerPolicyVersion":"rag-answer-policy-v1",
                          "modelProvider":"local",
                          "modelName":"deterministic-extractive-v1"
                        }
                        """, MediaType.APPLICATION_JSON));

        RagAnswerResponse response = service.answer(UUID.randomUUID(),
                new RagAnswerRequest("How do I configure a connector?", 4, "trace-1"));

        assertThat(response.answer()).contains("TEST connector");
        assertThat(response.citations()).hasSize(1);
        assertThat(response.promptTemplateVersion()).isEqualTo("rag-answer-template-v1");
        assertThat(response.modelProvider()).isEqualTo("local");
        verify(auditLogService).record(any(), eq(AuditAction.ASSISTANT_QUESTION_ASKED),
                eq("ASSISTANT"), eq("trace-1"), eq("RAG assistant question"), anyMap());
        server.verify();
    }

    @Test
    void answer_whenAuthTokenConfigured_sendsBearerToken() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MerchantAuditLogService auditLogService = mock(MerchantAuditLogService.class);
        AiAssistantService service = service(true, restTemplate, propertiesWithAuth(true), auditLogService);

        server.expect(once(), requestTo("http://ai-service:8090/v1/rag/answer"))
                .andExpect(header("Authorization", "Bearer test-rag-token"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {
                          "answer":"Use TEST connector settings.",
                          "citations":[],
                          "refusalReason":null,
                          "confidence":"medium",
                          "retrievedChunks":[],
                          "promptTemplateVersion":"rag-answer-template-v1",
                          "answerPolicyVersion":"rag-answer-policy-v1",
                          "modelProvider":"local",
                          "modelName":"deterministic-extractive-v1"
                        }
                        """, MediaType.APPLICATION_JSON));

        RagAnswerResponse response = service.answer(UUID.randomUUID(),
                new RagAnswerRequest("How do I configure a connector?", 4, "trace-auth"));

        assertThat(response.refusalReason()).isNull();
        server.verify();
    }

    @Test
    void answer_alwaysScopesAudienceToMerchant() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiAssistantService service = service(true, restTemplate, properties(true), mock(MerchantAuditLogService.class));

        // A merchant-realm caller cannot escalate to platform-admin/operator/engineering docs:
        // the gateway resolves audience itself and only ever asks the AI service for merchant scope.
        server.expect(once(), requestTo("http://ai-service:8090/v1/rag/answer"))
                .andExpect(jsonPath("$.audience").value("merchant"))
                .andRespond(withSuccess("""
                        {
                          "answer":"Merchant-scoped answer.",
                          "citations":[],
                          "refusalReason":null,
                          "confidence":"medium",
                          "retrievedChunks":[],
                          "promptTemplateVersion":"rag-answer-template-v1",
                          "answerPolicyVersion":"rag-answer-policy-v1",
                          "modelProvider":"local",
                          "modelName":"deterministic-extractive-v1"
                        }
                        """, MediaType.APPLICATION_JSON));

        service.answer(UUID.randomUUID(),
                new RagAnswerRequest("Show me the operations runbook", 4, "trace-scope"));

        server.verify();
    }

    @Test
    void status_whenDisabled_returnsDisabledMetadata() {
        AiAssistantService service = service(false, new RestTemplate(), properties(false), mock(MerchantAuditLogService.class));

        RagIndexStatusResponse response = service.status(UUID.randomUUID());

        assertThat(response.indexVersion()).isEqualTo("disabled");
        assertThat(response.chunkCount()).isZero();
    }

    @Test
    void status_forwardsToAiService() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiAssistantService service = service(true, restTemplate, propertiesWithAuth(true), mock(MerchantAuditLogService.class));

        server.expect(once(), requestTo("http://ai-service:8090/v1/rag/status"))
                .andExpect(header("Authorization", "Bearer test-rag-token"))
                .andRespond(withSuccess("""
                        {
                          "indexVersion":"rag-docs-v1",
                          "gitCommit":"abc123",
                          "lastIndexedAt":"2026-07-06T00:00:00+00:00",
                          "chunkCount":10,
                          "sourceCount":2,
                          "vectorBackend":"qdrant",
                          "promptTemplateVersion":"rag-answer-template-v1",
                          "answerPolicyVersion":"rag-answer-policy-v1",
                          "modelProvider":"local",
                          "modelName":"deterministic-extractive-v1",
                          "sources":[]
                        }
                        """, MediaType.APPLICATION_JSON));

        RagIndexStatusResponse response = service.status(UUID.randomUUID());

        assertThat(response.indexVersion()).isEqualTo("rag-docs-v1");
        assertThat(response.vectorBackend()).isEqualTo("qdrant");
        assertThat(response.promptTemplateVersion()).isEqualTo("rag-answer-template-v1");
        server.verify();
    }

    @Test
    void answer_whenBudgetExceeded_refusesWithoutCallingAiService() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MerchantAuditLogService auditLogService = mock(MerchantAuditLogService.class);
        AiAssistantProperties properties = new AiAssistantProperties(
                true, "http://ai-service:8090", 1_000, 5_000, "", 0, 12_000, 3_600);
        AiAssistantService service = service(true, restTemplate, properties, auditLogService);

        RagAnswerResponse response = service.answer(UUID.randomUUID(),
                new RagAnswerRequest("How do I configure a connector?", 4, "trace-budget"));

        assertThat(response.refusalReason()).isEqualTo("request_budget_exceeded");
        verify(auditLogService).record(any(), eq(AuditAction.ASSISTANT_QUESTION_ASKED),
                eq("ASSISTANT"), eq("trace-budget"), eq("RAG assistant question"), anyMap());
        server.verify();
    }

    @Test
    void answer_whenAiServiceFails_returnsControlledUnavailableResponse() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        MerchantAuditLogService auditLogService = mock(MerchantAuditLogService.class);
        AiAssistantService service = service(true, restTemplate, properties(true), auditLogService);

        server.expect(once(), requestTo("http://ai-service:8090/v1/rag/answer"))
                .andRespond(withServerError());

        RagAnswerResponse response = service.answer(UUID.randomUUID(),
                new RagAnswerRequest("How do I configure a connector?", 4, "trace-down"));

        assertThat(response.refusalReason()).isEqualTo("assistant_unavailable");
        assertThat(response.answer()).contains("temporarily unavailable");
        verify(auditLogService).record(any(), eq(AuditAction.ASSISTANT_QUESTION_ASKED),
                eq("ASSISTANT"), eq("trace-down"), eq("RAG assistant question"), anyMap());
        server.verify();
    }

    @Test
    void status_whenAiServiceFails_returnsUnavailableMetadata() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiAssistantService service = service(true, restTemplate, properties(true), mock(MerchantAuditLogService.class));

        server.expect(once(), requestTo("http://ai-service:8090/v1/rag/status"))
                .andRespond(withServerError());

        RagIndexStatusResponse response = service.status(UUID.randomUUID());

        assertThat(response.indexVersion()).isEqualTo("unavailable");
        assertThat(response.vectorBackend()).isEqualTo("unknown");
        server.verify();
    }

    private AiAssistantService service(boolean enabled,
                                       RestTemplate restTemplate,
                                       AiAssistantProperties properties,
                                       MerchantAuditLogService auditLogService) {
        return new AiAssistantService(
                properties,
                restTemplate,
                new AiAssistantBudgetService(properties),
                auditLogService
        );
    }

    private AiAssistantProperties properties(boolean enabled) {
        return new AiAssistantProperties(enabled, "http://ai-service:8090", 1_000, 5_000,
                "", 60, 12_000, 3_600);
    }

    private AiAssistantProperties propertiesWithAuth(boolean enabled) {
        return new AiAssistantProperties(enabled, "http://ai-service:8090", 1_000, 5_000,
                "test-rag-token", 60, 12_000, 3_600);
    }
}
