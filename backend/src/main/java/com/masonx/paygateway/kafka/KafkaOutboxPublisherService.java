package com.masonx.paygateway.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.metrics.PaymentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnProperty(prefix = "app.kafka.outbox", name = "enabled", havingValue = "true")
public class KafkaOutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboxPublisherService.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final KafkaOutboxProperties properties;
    private final TransactionTemplate txTemplate;
    private final PaymentMetrics metrics;

    public KafkaOutboxPublisherService(OutboxEventRepository outboxEventRepository,
                                       KafkaTemplate<String, String> kafkaTemplate,
                                       ObjectMapper objectMapper,
                                       KafkaOutboxProperties properties,
                                       PlatformTransactionManager transactionManager,
                                       PaymentMetrics metrics) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.kafka.outbox.poll-delay-ms:1000}")
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository
                .findByKafkaPublishedFalseOrderByCreatedAtAsc(PageRequest.of(0, properties.getBatchSize()));

        for (OutboxEvent event : pending) {
            publishOne(event);
        }
    }

    private void publishOne(OutboxEvent event) {
        try {
            String message = buildMessage(event);
            kafkaTemplate.send(properties.getTopic(), event.getResourceId().toString(), message)
                    .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);

            txTemplate.executeWithoutResult(status -> outboxEventRepository
                    .findByIdKafkaUnpublishedForUpdate(event.getId())
                    .ifPresent(fresh -> {
                        fresh.markKafkaPublished(Instant.now());
                        outboxEventRepository.save(fresh);
                    }));

            metrics.recordKafkaOutboxPublished(event.getEventType());
        } catch (Exception ex) {
            recordFailure(event.getId(), ex);
        }
    }

    private String buildMessage(OutboxEvent event) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("outboxEventId", event.getId().toString());
        root.put("merchantId", event.getMerchantId().toString());
        root.put("eventType", event.getEventType());
        root.put("resourceId", event.getResourceId().toString());
        root.put("createdAt", event.getCreatedAt().toString());
        root.set("payload", parsePayload(event.getPayload()));
        return objectMapper.writeValueAsString(root);
    }

    private JsonNode parsePayload(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            return TextNode.valueOf(payload);
        }
    }

    private void recordFailure(UUID eventId, Exception ex) {
        String message = truncate(ex.getMessage(), 1_000);
        txTemplate.executeWithoutResult(status -> outboxEventRepository
                .findByIdKafkaUnpublishedForUpdate(eventId)
                .ifPresent(fresh -> {
                    fresh.recordKafkaPublishFailure(message);
                    outboxEventRepository.save(fresh);
                }));
        metrics.recordKafkaOutboxFailed();
        log.warn("Failed to publish outbox event {} to Kafka: {}", eventId, message);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
