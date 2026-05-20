package com.masonx.paygateway.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.service.WebhookDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "app.kafka.webhook-consumer", name = "enabled", havingValue = "true")
public class KafkaWebhookConsumerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaWebhookConsumerService.class);

    private final ObjectMapper objectMapper;
    private final WebhookDeliveryService webhookDeliveryService;

    public KafkaWebhookConsumerService(ObjectMapper objectMapper,
                                       WebhookDeliveryService webhookDeliveryService) {
        this.objectMapper = objectMapper;
        this.webhookDeliveryService = webhookDeliveryService;
    }

    @KafkaListener(
            topics = "${app.kafka.outbox.topic:payment.lifecycle.events}",
            groupId = "${app.kafka.webhook-consumer.group-id:masonxpay-webhook-worker}")
    public void consumePaymentLifecycleEvent(String message) {
        UUID outboxEventId = extractOutboxEventId(message);
        if (outboxEventId == null) {
            return;
        }
        webhookDeliveryService.processOutboxEvent(outboxEventId);
    }

    private UUID extractOutboxEventId(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            JsonNode idNode = root.get("outboxEventId");
            if (idNode == null || idNode.asText("").isBlank()) {
                log.warn("Skipping Kafka lifecycle event without outboxEventId");
                return null;
            }
            return UUID.fromString(idNode.asText());
        } catch (Exception ex) {
            log.warn("Skipping malformed Kafka lifecycle event: {}", ex.getMessage());
            return null;
        }
    }
}
