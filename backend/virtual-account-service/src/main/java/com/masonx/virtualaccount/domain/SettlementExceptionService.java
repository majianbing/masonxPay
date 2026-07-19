package com.masonx.virtualaccount.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.common.error.BusinessException;
import com.masonx.common.id.MasonXIdPrefix;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionSource;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

/**
 * Parks settlement events that could not post. Parking is the terminal action
 * for a failed delivery: the event payload and failure reason are durably
 * recorded for the ops retry workflow, and the consumer can safely ack.
 *
 * <p>park() runs in its own transaction (callers are non-transactional consume
 * paths; the failed posting attempt has already rolled back independently —
 * including its inbox reservation, so a later retry re-drives cleanly).
 *
 * <p>Exposes the {@code va_settlement_exceptions_open} gauge so Prometheus can
 * alert on a growing ops backlog.
 */
@Service
public class SettlementExceptionService {

    private static final Logger log = LoggerFactory.getLogger(SettlementExceptionService.class);

    private final SettlementExceptionRepository repo;
    private final SnowflakeIdGenerator idGen;
    private final ObjectMapper objectMapper;

    public SettlementExceptionService(SettlementExceptionRepository repo,
                                      SnowflakeIdGenerator idGen,
                                      ObjectMapper objectMapper,
                                      MeterRegistry meterRegistry) {
        this.repo = repo;
        this.idGen = idGen;
        this.objectMapper = objectMapper;
        Gauge.builder("va_settlement_exceptions_open", repo,
                        r -> r.count(SettlementExceptionStatus.OPEN))
                .description("Settlement events parked and awaiting ops action")
                .register(meterRegistry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void park(SettlementExceptionSource source, String eventId, String eventType,
                     SettlementExceptionReason reason, String errorDetail, Object payload) {
        String exceptionId = idGen.generate(MasonXIdPrefix.SETTLEMENT_EXCEPTION.prefix());
        repo.upsertOpen(exceptionId, source, eventId, eventType, reason, errorDetail, toJson(payload));
        log.error("Settlement event parked: eventId={} source={} reason={} detail={}",
                eventId, source, reason, errorDetail);
    }

    /** Maps a posting-time business failure to a parking reason. */
    public static SettlementExceptionReason reasonFor(BusinessException e) {
        return switch (e.code()) {
            case "VA_INSUFFICIENT_BALANCE" -> SettlementExceptionReason.INSUFFICIENT_BALANCE;
            case "VA_ACCOUNT_NOT_FOUND", "VA_ACCOUNT_NOT_ACTIVE" ->
                    SettlementExceptionReason.LEDGER_ACCOUNT_NOT_FOUND;
            default -> SettlementExceptionReason.POSTING_FAILED;
        };
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Parking must not fail because the payload would not serialize — record
            // valid JSON with what we can so the event stays visible to ops.
            log.error("Failed to serialize parked payload: {}", e.getMessage());
            var fallback = objectMapper.createObjectNode();
            fallback.put("serializationError", e.getMessage());
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (JsonProcessingException impossible) {
                return "{\"serializationError\":\"unknown\"}";
            }
        }
    }
}
