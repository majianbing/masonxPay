package com.masonx.paygateway.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.metrics.PaymentMetrics;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaOutboxPublisherServiceTest {

    private static final String TOPIC = "payment.lifecycle.events";

    @Mock OutboxEventRepository outboxEventRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock PaymentMetrics metrics;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KafkaOutboxPublisherService publisher;

    @BeforeEach
    void setUp() {
        KafkaOutboxProperties properties = new KafkaOutboxProperties();
        properties.setTopic(TOPIC);
        properties.setBatchSize(10);
        properties.setSendTimeoutMs(1_000);

        publisher = new KafkaOutboxPublisherService(
                outboxEventRepository,
                kafkaTemplate,
                objectMapper,
                properties,
                new NoopTransactionManager(),
                metrics);
    }

    @Test
    void publishPendingEvents_sendSucceeds_marksKafkaPublishedAndEmitsEnvelope() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OutboxEvent event = event(eventId, merchantId, "payment_intent.succeeded", resourceId,
                "{\"amount\":1000,\"currency\":\"USD\"}");

        when(outboxEventRepository.findByKafkaPublishedFalseOrderByCreatedAtAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
        when(outboxEventRepository.findByIdKafkaUnpublishedForUpdate(eventId))
                .thenReturn(Optional.of(event));
        when(kafkaTemplate.send(eq(TOPIC), eq(resourceId.toString()), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(new SendResult<>(null, (RecordMetadata) null)));

        publisher.publishPendingEvents();

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq(resourceId.toString()), messageCaptor.capture());
        JsonNode message = objectMapper.readTree(messageCaptor.getValue());
        assertThat(message.get("outboxEventId").asText()).isEqualTo(eventId.toString());
        assertThat(message.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(message.get("eventType").asText()).isEqualTo("payment_intent.succeeded");
        assertThat(message.get("resourceId").asText()).isEqualTo(resourceId.toString());
        assertThat(message.get("payload").get("amount").asLong()).isEqualTo(1_000L);

        assertThat(event.isKafkaPublished()).isTrue();
        assertThat(event.getKafkaPublishedAt()).isNotNull();
        assertThat(event.getKafkaLastError()).isNull();
        verify(outboxEventRepository).save(event);
        verify(metrics).recordKafkaOutboxPublished("payment_intent.succeeded");
    }

    @Test
    void publishPendingEvents_sendFails_recordsFailureAndLeavesEventUnpublished() {
        UUID eventId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OutboxEvent event = event(eventId, UUID.randomUUID(), "payment_intent.failed", resourceId,
                "{\"failureCode\":\"api_error\"}");
        CompletableFuture<SendResult<String, String>> failedSend = new CompletableFuture<>();
        failedSend.completeExceptionally(new RuntimeException("broker unavailable"));

        when(outboxEventRepository.findByKafkaPublishedFalseOrderByCreatedAtAsc(any(Pageable.class)))
                .thenReturn(List.of(event));
        when(outboxEventRepository.findByIdKafkaUnpublishedForUpdate(eventId))
                .thenReturn(Optional.of(event));
        when(kafkaTemplate.send(eq(TOPIC), eq(resourceId.toString()), any(String.class)))
                .thenReturn(failedSend);

        publisher.publishPendingEvents();

        assertThat(event.isKafkaPublished()).isFalse();
        assertThat(event.getKafkaPublishAttempts()).isEqualTo(1);
        assertThat(event.getKafkaLastError()).contains("broker unavailable");
        verify(outboxEventRepository).save(event);
        verify(metrics).recordKafkaOutboxFailed();
        verify(metrics, never()).recordKafkaOutboxPublished(any());
    }

    @Test
    void paymentLifecycleEventsTopic_usesConfiguredTopicAndPartitionCount() {
        KafkaOutboxProperties properties = new KafkaOutboxProperties();
        properties.setTopic("custom.payment.events");
        properties.setTopicPartitions(24);

        NewTopic topic = new KafkaTopicConfig().paymentLifecycleEventsTopic(properties);

        assertThat(topic.name()).isEqualTo("custom.payment.events");
        assertThat(topic.numPartitions()).isEqualTo(24);
        assertThat(topic.replicationFactor()).isEqualTo((short) 1);
    }

    private static OutboxEvent event(UUID eventId,
                                     UUID merchantId,
                                     String eventType,
                                     UUID resourceId,
                                     String payload) {
        OutboxEvent event = new OutboxEvent(merchantId, eventType, resourceId, payload);
        ReflectionTestUtils.setField(event, "id", eventId);
        return event;
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
