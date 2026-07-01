package com.masonx.rail.service;

import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.common.tenant.MerchantId;
import com.masonx.common.tenant.Mode;
import com.masonx.common.tenant.TenantRef;
import com.masonx.contracts.EventEnvelope;
import com.masonx.contracts.rail.MoneyMovementType;
import com.masonx.contracts.rail.PaymentRail;
import com.masonx.contracts.rail.RailSettlementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Publishes {@link RailSettlementEvent} to Kafka after a card sale or bank transfer
 * reaches a terminal settlement state.
 *
 * <p>Rail-service operates in TEST mode only (simulator rail). The {@code TenantRef}
 * is constructed with {@link Mode#TEST} and no orgId.
 */
@Component
public class RailSettlementEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RailSettlementEventPublisher.class);

    private final KafkaTemplate<String, RailSettlementEvent> kafka;
    private final SnowflakeIdGenerator idGen;
    private final String topic;

    public RailSettlementEventPublisher(
            KafkaTemplate<String, RailSettlementEvent> kafka,
            SnowflakeIdGenerator idGen,
            @Value("${rail.kafka.settlement-topic:rail.settlement.events}") String topic) {
        this.kafka = kafka;
        this.idGen = idGen;
        this.topic = topic;
    }

    public void publishCardSale(String paymentId, String merchantId, String networkName,
                                String maskedPan, BigDecimal amount, String currency) {
        publish(buildEvent(paymentId, merchantId, PaymentRail.CARD_ISO8583,
                MoneyMovementType.CARD_SALE, amount, currency, networkName, maskedPan));
    }

    public void publishCardReversal(String paymentId, String merchantId, String networkName,
                                    String maskedPan, BigDecimal amount, String currency) {
        publish(buildEvent(paymentId, merchantId, PaymentRail.CARD_ISO8583,
                MoneyMovementType.CARD_REVERSAL, amount, currency, networkName, maskedPan));
    }

    public void publishBankTransferSettled(String paymentId, String merchantId,
                                           String networkName, BigDecimal amount, String currency) {
        publish(buildEvent(paymentId, merchantId, PaymentRail.BANK_ISO20022,
                MoneyMovementType.BANK_CREDIT_TRANSFER, amount, currency, networkName, null));
    }

    public void publishBankReturn(String paymentId, String merchantId,
                                  String networkName, BigDecimal amount, String currency) {
        publish(buildEvent(paymentId, merchantId, PaymentRail.BANK_ISO20022,
                MoneyMovementType.BANK_RETURN, amount, currency, networkName, null));
    }

    private RailSettlementEvent buildEvent(String paymentId, String merchantId,
                                           PaymentRail rail, MoneyMovementType movementType,
                                           BigDecimal amount, String currency,
                                           String networkName, String maskedPan) {
        TenantRef tenant = new TenantRef(Mode.TEST, null,
                new MerchantId(UUID.fromString(merchantId)));
        EventEnvelope envelope = new EventEnvelope(
                idGen.generate(MasonXIdPrefix.EVENT.prefix()),
                RailSettlementEvent.TYPE,
                RailSettlementEvent.SCHEMA_VERSION,
                Instant.now(),
                paymentId,
                tenant);
        return new RailSettlementEvent(
                envelope, paymentId, rail, movementType,
                currency, amount,
                null,        // vccAccountId — VA resolves via maskedPan
                networkName, // receivableAccountId — VA resolves via networkName lookup
                networkName,
                Instant.now(),
                merchantId,
                maskedPan);
    }

    private void publish(RailSettlementEvent event) {
        kafka.send(topic, event.railPaymentId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish RailSettlementEvent paymentId={} type={}: {}",
                                event.railPaymentId(), event.movementType(), ex.getMessage(), ex);
                    } else {
                        log.info("RailSettlementEvent published paymentId={} type={} topic={}",
                                event.railPaymentId(), event.movementType(), topic);
                    }
                });
    }
}
