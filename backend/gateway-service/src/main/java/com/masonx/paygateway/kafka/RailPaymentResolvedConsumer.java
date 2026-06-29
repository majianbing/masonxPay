package com.masonx.paygateway.kafka;

import com.masonx.contracts.rail.RailPaymentResolvedEvent;
import com.masonx.paygateway.service.PaymentIntentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@link RailPaymentResolvedEvent} published by rail-service after a reversal
 * discipline completes on a card auth that previously timed out (UNKNOWN state).
 *
 * <p>Calls {@link PaymentIntentService#resolveRailPayment} which is idempotent — safe
 * under Kafka's at-least-once delivery guarantee.
 */
@Component
@ConditionalOnProperty(prefix = "app.rail", name = "enabled", havingValue = "true")
public class RailPaymentResolvedConsumer {

    private static final Logger log = LoggerFactory.getLogger(RailPaymentResolvedConsumer.class);

    private final PaymentIntentService paymentIntentService;

    public RailPaymentResolvedConsumer(PaymentIntentService paymentIntentService) {
        this.paymentIntentService = paymentIntentService;
    }

    @KafkaListener(
            topics         = "${app.rail.resolved-topic:rail.payment.resolved}",
            containerFactory = "railResolvedListenerContainerFactory")
    public void onResolvedEvent(RailPaymentResolvedEvent event) {
        if (event == null || event.railPaymentId() == null) {
            log.warn("RailPaymentResolvedConsumer: received null or incomplete event — skipping");
            return;
        }
        log.info("RailPaymentResolvedConsumer: received railPaymentId={} outcome={}",
                event.railPaymentId(), event.outcome());
        try {
            paymentIntentService.resolveRailPayment(event.railPaymentId(), event.outcome());
        } catch (Exception e) {
            log.error("RailPaymentResolvedConsumer: failed to resolve paymentId={}: {}",
                    event.railPaymentId(), e.getMessage(), e);
            throw e; // re-throw so Kafka retries or DLQ can handle it
        }
    }
}
