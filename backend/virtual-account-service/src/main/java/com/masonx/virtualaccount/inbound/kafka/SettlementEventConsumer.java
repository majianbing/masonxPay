package com.masonx.virtualaccount.inbound.kafka;

import com.masonx.contracts.settlement.SettlementEvent;
import com.masonx.virtualaccount.domain.LedgerSettlementHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Thin inbound adapter — translates Kafka delivery into a domain call.
 * Idempotency is enforced inside LedgerFacade#postAllIfNew, not here.
 */
@Component
public class SettlementEventConsumer {

    private final SettlementEventMapper  mapper;
    private final LedgerSettlementHandler handler;

    public SettlementEventConsumer(SettlementEventMapper mapper,
                                   LedgerSettlementHandler handler) {
        this.mapper  = mapper;
        this.handler = handler;
    }

    @KafkaListener(
            topics = "${va.kafka.settlement-topic}",
            autoStartup = "${va.kafka.consumer.enabled:true}")
    public void onSettlement(SettlementEvent event) {
        handler.handle(mapper.toCommand(event));
    }
}
