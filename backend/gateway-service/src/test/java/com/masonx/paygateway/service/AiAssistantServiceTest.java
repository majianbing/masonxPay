package com.masonx.paygateway.service;

import com.masonx.paygateway.config.AiAssistantProperties;
import com.masonx.paygateway.web.dto.RagAnswerRequest;
import com.masonx.paygateway.web.dto.RagAnswerResponse;
import com.masonx.paygateway.web.dto.RagIndexStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAssistantServiceTest {

    @Test
    void answer_whenDisabled_refusesWithoutCallingAiService() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiAssistantService service = new AiAssistantService(
                new AiAssistantProperties(false, "http://ai-service:8090", 1_000, 5_000),
                restTemplate
        );

        RagAnswerResponse response = service.answer(UUID.randomUUID(),
                new RagAnswerRequest("How do I configure a connector?", "merchant", 4, null));

        assertThat(response.refusalReason()).isEqualTo("assistant_disabled");
        server.verify();
    }

    @Test
    void answer_forwardsDocsOnlyQuestionToAiService() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiAssistantService service = new AiAssistantService(
                new AiAssistantProperties(true, "http://ai-service:8090", 1_000, 5_000),
                restTemplate
        );

        server.expect(once(), requestTo("http://ai-service:8090/v1/rag/answer"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andRespond(withSuccess("""
                        {
                          "answer":"Use TEST connector settings.",
                          "citations":[{"sourcePath":"docs/engineering/connector-development.md","headingPath":"Connectors","sourceType":"engineering","stability":"stable"}],
                          "refusalReason":null,
                          "confidence":"medium",
                          "retrievedChunks":[]
                        }
                        """, MediaType.APPLICATION_JSON));

        RagAnswerResponse response = service.answer(UUID.randomUUID(),
                new RagAnswerRequest("How do I configure a connector?", "developer", 4, "trace-1"));

        assertThat(response.answer()).contains("TEST connector");
        assertThat(response.citations()).hasSize(1);
        server.verify();
    }

    @Test
    void answer_rejectsUnknownAudience() {
        AiAssistantService service = new AiAssistantService(
                new AiAssistantProperties(true, "http://ai-service:8090", 1_000, 5_000),
                new RestTemplate()
        );

        assertThatThrownBy(() -> service.answer(UUID.randomUUID(),
                new RagAnswerRequest("Question", "admin", 4, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid assistant audience");
    }

    @Test
    void status_whenDisabled_returnsDisabledMetadata() {
        AiAssistantService service = new AiAssistantService(
                new AiAssistantProperties(false, "http://ai-service:8090", 1_000, 5_000),
                new RestTemplate()
        );

        RagIndexStatusResponse response = service.status(UUID.randomUUID());

        assertThat(response.indexVersion()).isEqualTo("disabled");
        assertThat(response.chunkCount()).isZero();
    }

    @Test
    void status_forwardsToAiService() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        AiAssistantService service = new AiAssistantService(
                new AiAssistantProperties(true, "http://ai-service:8090", 1_000, 5_000),
                restTemplate
        );

        server.expect(once(), requestTo("http://ai-service:8090/v1/rag/status"))
                .andRespond(withSuccess("""
                        {
                          "indexVersion":"rag-docs-v1",
                          "gitCommit":"abc123",
                          "lastIndexedAt":"2026-07-06T00:00:00+00:00",
                          "chunkCount":10,
                          "sourceCount":2,
                          "vectorBackend":"qdrant",
                          "sources":[]
                        }
                        """, MediaType.APPLICATION_JSON));

        RagIndexStatusResponse response = service.status(UUID.randomUUID());

        assertThat(response.indexVersion()).isEqualTo("rag-docs-v1");
        assertThat(response.vectorBackend()).isEqualTo("qdrant");
        server.verify();
    }
}
