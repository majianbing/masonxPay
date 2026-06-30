package com.masonx.virtualaccount.inbound.kafka;

import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.CardRailSettlementHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Thin inbound adapter — translates rail settlement Kafka delivery into a domain call.
 * Idempotency is enforced inside {@link CardRailSettlementHandler} via
 * {@code LedgerFacade#postIfNew}.
 */
@Component
public class RailSettlementEventConsumer {

    private final CardRailSettlementHandler handler;

    public RailSettlementEventConsumer(CardRailSettlementHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(
            topics         = "${va.kafka.rail-settlement-topic:rail.settlement.events}",
            containerFactory = "railSettlementListenerContainerFactory",
            autoStartup    = "${va.kafka.consumer.enabled:true}")
    public void onRailSettlement(RailSettlementEvent event) {
        handler.handle(event);
    }
}
