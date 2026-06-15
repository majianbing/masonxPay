package com.masonx.virtualaccount.inbound.kafka;

import com.masonx.contracts.settlement.SettlementEvent;
import com.masonx.virtualaccount.domain.RecordSettlementCommand;
import org.springframework.stereotype.Component;

/**
 * Anti-corruption layer: translates the wire contract ({@link SettlementEvent})
 * into a VA-native {@link RecordSettlementCommand}. This is the only place that
 * knows the gateway's event vocabulary; the domain never imports contracts.
 */
@Component
public class SettlementEventMapper {

    public RecordSettlementCommand toCommand(SettlementEvent event) {
        return new RecordSettlementCommand(
                event.envelope().eventId(),
                event.envelope().tenant(),
                event.paymentId(),
                event.providerRef());
    }
}
