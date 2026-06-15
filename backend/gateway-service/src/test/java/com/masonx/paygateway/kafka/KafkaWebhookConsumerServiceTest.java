package com.masonx.paygateway.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.service.WebhookDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class KafkaWebhookConsumerServiceTest {

    @Mock WebhookDeliveryService webhookDeliveryService;

    private KafkaWebhookConsumerService consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaWebhookConsumerService(
                new ObjectMapper(),
                webhookDeliveryService);
    }

    @Test
    void consumePaymentLifecycleEvent_validEnvelope_processesOutboxEvent() {
        UUID outboxEventId = UUID.randomUUID();

        consumer.consumePaymentLifecycleEvent("""
                {"outboxEventId":"%s","eventType":"payment_intent.succeeded"}
                """.formatted(outboxEventId));

        verify(webhookDeliveryService).processOutboxEvent(outboxEventId);
    }

    @Test
    void consumePaymentLifecycleEvent_missingOutboxEventId_skipsMessage() {
        consumer.consumePaymentLifecycleEvent("{\"eventType\":\"payment_intent.succeeded\"}");

        verifyNoInteractions(webhookDeliveryService);
    }

    @Test
    void consumePaymentLifecycleEvent_invalidOutboxEventId_skipsMessage() {
        consumer.consumePaymentLifecycleEvent("{\"outboxEventId\":\"not-a-uuid\"}");

        verify(webhookDeliveryService, never()).processOutboxEvent(any());
    }
}
