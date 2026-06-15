package com.masonx.virtualaccount.domain;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Skeleton handler: records nothing yet, just logs. Replace with the ledger
 * posting implementation during the VA domain design.
 */
@Component
public class LoggingSettlementHandler implements SettlementHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingSettlementHandler.class);

    @Override
    public void handle(RecordSettlementCommand command) {
        log.info("VA received settlement: eventId={} paymentId={} merchant={} mode={} (skeleton: no ledger posting yet)",
                command.sourceEventId(),
                command.paymentId(),
                command.tenant().merchantId().value(),
                command.tenant().mode());
    }
}
