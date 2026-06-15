package com.masonx.virtualaccount.inbound.kafka;

import com.masonx.contracts.settlement.SettlementEvent;
import com.masonx.virtualaccount.domain.SettlementHandler;
import com.masonx.virtualaccount.inbound.InboxRepository;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound adapter for settlement events. At-least-once delivery is made
 * effectively exactly-once by the inbox dedup before the domain runs. Manual
 * offset commit is unnecessary here because the dedup is the safety net; a
 * redelivered event is skipped.
 */
@Component
public class SettlementEventConsumer {

    private final InboxRepository inbox;
    private final SettlementEventMapper mapper;
    private final SettlementHandler handler;

    public SettlementEventConsumer(InboxRepository inbox,
                                   SettlementEventMapper mapper,
                                   SettlementHandler handler) {
        this.inbox = inbox;
        this.mapper = mapper;
        this.handler = handler;
    }

    @KafkaListener(
            topics = "${va.kafka.settlement-topic}",
            autoStartup = "${va.kafka.consumer.enabled:true}")
    public void onSettlement(SettlementEvent event) {
        if (!inbox.markProcessed(event.envelope().eventId(), event.envelope().eventType())) {
            return; // duplicate delivery — already processed
        }
        handler.handle(mapper.toCommand(event));
    }
}
