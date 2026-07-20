package com.masonx.virtualaccount.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.common.error.BusinessException;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.virtualaccount.domain.CardRailSettlementHandler;
import com.masonx.virtualaccount.domain.LedgerSettlementHandler;
import com.masonx.virtualaccount.domain.SettlementExceptionRepository;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionStatus;
import com.masonx.virtualaccount.domain.dto.RecordSettlementCommand;
import com.masonx.virtualaccount.domain.po.SettlementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Re-drives a parked settlement event through its original handler.
 *
 * <p>Safe to repeat: posting is idempotent (inbox + UNIQUE(ledger_account_id,
 * source_event_id, source_event_leg)), and if the event still cannot post, the
 * handler re-parks it
 * — the upsert bumps delivery_count on the same row, which is how this service
 * detects that the retry failed without needing a return value from the handler.
 */
@Service
public class SettlementExceptionRetryService {

    private static final Logger log = LoggerFactory.getLogger(SettlementExceptionRetryService.class);

    private final SettlementExceptionRepository repo;
    private final CardRailSettlementHandler cardRailHandler;
    private final LedgerSettlementHandler ledgerHandler;
    private final ObjectMapper objectMapper;

    public SettlementExceptionRetryService(SettlementExceptionRepository repo,
                                           CardRailSettlementHandler cardRailHandler,
                                           LedgerSettlementHandler ledgerHandler,
                                           ObjectMapper objectMapper) {
        this.repo = repo;
        this.cardRailHandler = cardRailHandler;
        this.ledgerHandler = ledgerHandler;
        this.objectMapper = objectMapper;
    }

    /** @return true if the event posted (or deduped) and the exception is now RESOLVED. */
    public boolean retry(String exceptionId) {
        SettlementException row = repo.findById(exceptionId)
                .orElseThrow(() -> new BusinessException("VA_NOT_FOUND",
                        "Settlement exception not found: " + exceptionId, 404));
        if (row.status() != SettlementExceptionStatus.OPEN) {
            throw new BusinessException("VA_EXCEPTION_NOT_OPEN",
                    "Only OPEN exceptions can be retried; status=" + row.status());
        }

        int deliveriesBefore = row.deliveryCount();
        try {
            redeliver(row);
        } catch (JsonProcessingException e) {
            repo.incrementRetryCount(exceptionId);
            throw new BusinessException("VA_EXCEPTION_PAYLOAD_INVALID",
                    "Stored payload cannot be deserialized: " + e.getOriginalMessage());
        } catch (RuntimeException e) {
            repo.incrementRetryCount(exceptionId);
            throw new BusinessException("VA_RETRY_FAILED",
                    "Retry raised " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        SettlementException after = repo.findById(exceptionId).orElseThrow(
                () -> new IllegalStateException("Exception row vanished during retry: " + exceptionId));
        if (after.deliveryCount() > deliveriesBefore) {
            // Handler re-parked the same event: still unpostable.
            repo.incrementRetryCount(exceptionId);
            log.info("Settlement exception retry did not resolve: id={} reason={}",
                    exceptionId, after.reasonCode());
            return false;
        }

        repo.markResolved(exceptionId, "Re-driven successfully via retry API");
        log.info("Settlement exception resolved via retry: id={} eventId={}",
                exceptionId, row.eventId());
        return true;
    }

    private void redeliver(SettlementException row) throws JsonProcessingException {
        switch (row.source()) {
            case RAIL_SETTLEMENT -> cardRailHandler.handle(
                    objectMapper.readValue(row.payloadJson(), RailSettlementEvent.class));
            case GATEWAY_SETTLEMENT -> ledgerHandler.handle(
                    objectMapper.readValue(row.payloadJson(), RecordSettlementCommand.class));
            case UNKNOWN -> throw new BusinessException("VA_EXCEPTION_NOT_RETRYABLE",
                    "UNKNOWN-source exceptions hold an unparseable payload; inspect and discard");
        }
    }
}
