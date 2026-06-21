package com.masonx.virtualaccount.domain;

import com.masonx.virtualaccount.domain.api.SettlementHandler;
import com.masonx.virtualaccount.domain.dto.RecordSettlementCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaced by {@link LedgerSettlementHandler}. Kept as reference; not registered as a bean.
 */
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
