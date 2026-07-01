package com.masonx.rail.service;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.MerchantId;
import com.masonx.common.tenant.Mode;
import com.masonx.common.tenant.TenantRef;
import com.masonx.contracts.EventEnvelope;
import com.masonx.contracts.rail.RailPaymentResolvedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@link RailPaymentResolvedEvent} after a reversal discipline concludes
 * on a previously UNKNOWN card auth, so gateway-service can finalize the
 * {@code PaymentIntent} status asynchronously.
 */
@Component
public class RailPaymentResolvedPublisher {

    private static final Logger log = LoggerFactory.getLogger(RailPaymentResolvedPublisher.class);

    private final KafkaTemplate<String, RailPaymentResolvedEvent> kafka;
    private final SnowflakeIdGenerator idGen;
    private final String topic;

    public RailPaymentResolvedPublisher(
            KafkaTemplate<String, RailPaymentResolvedEvent> kafka,
            SnowflakeIdGenerator idGen,
            @Value("${rail.kafka.resolved-topic:rail.payment.resolved}") String topic) {
        this.kafka = kafka;
        this.idGen = idGen;
        this.topic = topic;
    }

    /**
     * @param railPaymentId the rail-service payment ID (stored as providerPaymentId in gateway)
     * @param merchantId    UUID string of the merchant — must be a valid UUID
     * @param outcome       "SUCCEEDED" or "FAILED"
     */
    public void publish(String railPaymentId, String merchantId, String outcome) {
        if (merchantId == null || merchantId.isBlank()) {
            log.warn("RailPaymentResolvedPublisher: skipping publish for paymentId={} — merchantId is blank",
                    railPaymentId);
            return;
        }
        TenantRef tenant;
        try {
            tenant = new TenantRef(Mode.TEST, null, new MerchantId(UUID.fromString(merchantId)));
        } catch (IllegalArgumentException e) {
            log.warn("RailPaymentResolvedPublisher: skipping publish for paymentId={} — merchantId '{}' is not a valid UUID",
                    railPaymentId, merchantId);
            return;
        }
        EventEnvelope envelope = new EventEnvelope(
                idGen.generate(MasonXIdPrefix.EVENT.prefix()),
                RailPaymentResolvedEvent.TYPE,
                RailPaymentResolvedEvent.SCHEMA_VERSION,
                Instant.now(),
                railPaymentId,
                tenant);
        RailPaymentResolvedEvent event = new RailPaymentResolvedEvent(envelope, railPaymentId, outcome);
        kafka.send(topic, railPaymentId, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish RailPaymentResolvedEvent paymentId={} outcome={}: {}",
                                railPaymentId, outcome, ex.getMessage(), ex);
                    } else {
                        log.info("RailPaymentResolvedEvent published paymentId={} outcome={} topic={}",
                                railPaymentId, outcome, topic);
                    }
                });
    }
}
