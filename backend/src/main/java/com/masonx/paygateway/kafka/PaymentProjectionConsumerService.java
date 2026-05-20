package com.masonx.paygateway.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.projection.PaymentReadModel;
import com.masonx.paygateway.domain.projection.PaymentReadModelRepository;
import com.masonx.paygateway.domain.projection.ProjectionEventStatus;
import com.masonx.paygateway.domain.projection.ProjectionProcessedEvent;
import com.masonx.paygateway.domain.projection.ProjectionProcessedEventRepository;
import com.masonx.paygateway.projection.PaymentReadModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "app.kafka.payment-projection", name = "enabled", havingValue = "true")
public class PaymentProjectionConsumerService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProjectionConsumerService.class);
    private static final String CONSUMER_NAME = "payment-read-model";

    private final ObjectMapper objectMapper;
    private final PaymentReadModelRepository readModelRepository;
    private final ProjectionProcessedEventRepository processedEventRepository;
    private final TransactionTemplate txTemplate;

    public PaymentProjectionConsumerService(ObjectMapper objectMapper,
                                            PaymentReadModelRepository readModelRepository,
                                            ProjectionProcessedEventRepository processedEventRepository,
                                            PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.readModelRepository = readModelRepository;
        this.processedEventRepository = processedEventRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    @KafkaListener(
            topics = "${app.kafka.outbox.topic:payment.lifecycle.events}",
            groupId = "${app.kafka.payment-projection.group-id:masonxpay-payment-projection}")
    public void consumePaymentLifecycleEvent(String message) {
        ProjectionEnvelope envelope;
        try {
            envelope = parseEnvelope(message);
        } catch (Exception ex) {
            log.warn("Skipping malformed projection event: {}", ex.getMessage());
            return;
        }
        if (envelope == null) {
            return;
        }
        txTemplate.executeWithoutResult(status -> process(envelope));
    }

    private void process(ProjectionEnvelope envelope) {
        if (processedEventRepository.existsById(envelope.outboxEventId())) {
            return;
        }

        try {
            if (envelope.eventType().startsWith("payment_intent.")) {
                projectPaymentIntent(envelope);
                recordProcessed(envelope, ProjectionEventStatus.PROCESSED, null);
            } else if (envelope.eventType().startsWith("refund.")) {
                projectRefund(envelope);
            }
        } catch (Exception ex) {
            recordProcessed(envelope, ProjectionEventStatus.FAILED, truncate(ex.getMessage(), 1_000));
            log.warn("Failed to apply projection event {} type {}: {}",
                    envelope.outboxEventId(), envelope.eventType(), ex.getMessage());
        }
    }

    private void projectPaymentIntent(ProjectionEnvelope envelope) {
        JsonNode payload = envelope.payload();
        UUID paymentIntentId = uuid(payload, "id").orElse(envelope.resourceId());

        PaymentReadModel model = readModelRepository.findById(paymentIntentId)
                .orElseGet(PaymentReadModel::new);
        PaymentReadModelMapper.applyPaymentPayload(model, payload, envelope.resourceId(), envelope.merchantId());
        if (model.getStatus() == null) {
            model.setStatus(eventStatus(envelope.eventType()));
        }
        applyEventMetadata(model, envelope);
        model.setSearchText(PaymentReadModelMapper.searchText(model));

        readModelRepository.save(model);
    }

    private void projectRefund(ProjectionEnvelope envelope) {
        JsonNode payload = envelope.payload();
        UUID paymentIntentId = uuid(payload, "paymentIntentId")
                .orElseThrow(() -> new IllegalArgumentException("Refund projection missing paymentIntentId"));
        UUID merchantId = uuid(payload, "merchantId").orElse(envelope.merchantId());

        Optional<PaymentReadModel> existing = readModelRepository.findByPaymentIntentIdAndMerchantId(paymentIntentId, merchantId);
        if (existing.isEmpty()) {
            recordProcessed(envelope, ProjectionEventStatus.FAILED,
                    "Payment read model missing for refund event paymentIntentId=" + paymentIntentId);
            return;
        }

        PaymentReadModel model = existing.get();
        model.setLastRefundId(uuid(payload, "id").orElse(envelope.resourceId()));
        model.setLastRefundStatus(text(payload, "status").orElse(eventStatus(envelope.eventType())));
        if ("refund.succeeded".equals(envelope.eventType())) {
            model.setRefundedAmountSucceeded(model.getRefundedAmountSucceeded() + payload.path("amount").asLong(0));
        }
        applyEventMetadata(model, envelope);
        model.setSearchText(PaymentReadModelMapper.searchText(model));
        readModelRepository.save(model);
        recordProcessed(envelope, ProjectionEventStatus.PROCESSED, null);
    }

    private ProjectionEnvelope parseEnvelope(String message) throws Exception {
        JsonNode root = objectMapper.readTree(message);
        Optional<UUID> outboxEventId = uuid(root, "outboxEventId");
        Optional<UUID> merchantId = uuid(root, "merchantId");
        Optional<UUID> resourceId = uuid(root, "resourceId");
        Optional<String> eventType = text(root, "eventType");
        if (outboxEventId.isEmpty() || merchantId.isEmpty() || resourceId.isEmpty() || eventType.isEmpty()) {
            log.warn("Skipping projection event with missing envelope fields");
            return null;
        }
        JsonNode payload = root.path("payload");
        if (payload.isMissingNode() || payload.isNull()) {
            log.warn("Skipping projection event {} without payload", outboxEventId.get());
            return null;
        }
        return new ProjectionEnvelope(
                outboxEventId.get(),
                merchantId.get(),
                eventType.get(),
                resourceId.get(),
                instant(root, "createdAt").orElse(Instant.now()),
                payload);
    }

    private void applyEventMetadata(PaymentReadModel model, ProjectionEnvelope envelope) {
        model.setLastEventId(envelope.outboxEventId());
        model.setLastEventType(envelope.eventType());
        model.setLastEventCreatedAt(envelope.createdAt());
    }

    private void recordProcessed(ProjectionEnvelope envelope, ProjectionEventStatus status, String error) {
        processedEventRepository.save(new ProjectionProcessedEvent(
                envelope.outboxEventId(),
                CONSUMER_NAME,
                envelope.merchantId(),
                envelope.eventType(),
                envelope.resourceId(),
                status,
                error));
    }

    private Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        String text = value.asText();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private Optional<UUID> uuid(JsonNode node, String field) {
        return text(node, field).map(UUID::fromString);
    }

    private Optional<Instant> instant(JsonNode node, String field) {
        return text(node, field).map(Instant::parse);
    }

    private String eventStatus(String eventType) {
        if (eventType.endsWith(".succeeded")) return "SUCCEEDED";
        if (eventType.endsWith(".failed")) return "FAILED";
        if (eventType.endsWith(".canceled")) return "CANCELED";
        if (eventType.endsWith(".requires_capture")) return "REQUIRES_CAPTURE";
        return eventType;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record ProjectionEnvelope(UUID outboxEventId,
                                      UUID merchantId,
                                      String eventType,
                                      UUID resourceId,
                                      Instant createdAt,
                                      JsonNode payload) {}
}
