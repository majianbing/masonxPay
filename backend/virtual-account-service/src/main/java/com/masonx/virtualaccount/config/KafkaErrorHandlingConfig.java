package com.masonx.virtualaccount.config;

import com.masonx.common.error.BusinessException;
import com.masonx.contracts.rail.RailSettlementEvent;
import com.masonx.contracts.settlement.SettlementEvent;
import com.masonx.virtualaccount.domain.SettlementExceptionService;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionSource;
import com.masonx.virtualaccount.inbound.kafka.SettlementEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

/**
 * Backstop for the settlement consumers: no event delivery may end in
 * spring-kafka's default log-and-skip. Known-unpostable events are parked by the
 * handlers themselves; this error handler covers everything else — transient
 * failures get retried with backoff, and once retries are exhausted (or the
 * failure is classified non-retryable) the recoverer parks the event in
 * settlement_exception instead of dropping it.
 *
 * <p>Spring Boot wires this bean into the auto-configured listener factory
 * (gateway settlement topic); {@link RailKafkaConsumerConfig} sets it on the
 * rail factory explicitly.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    public DefaultErrorHandler settlementErrorHandler(SettlementExceptionService exceptions,
                                                      SettlementEventMapper mapper) {
        ConsumerRecordRecoverer recoverer = (record, ex) -> {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            try {
                Object value = record.value();
                if (value instanceof RailSettlementEvent event) {
                    exceptions.park(SettlementExceptionSource.RAIL_SETTLEMENT,
                            event.envelope().eventId(), RailSettlementEvent.TYPE,
                            SettlementExceptionReason.UNEXPECTED_ERROR,
                            cause.getClass().getSimpleName() + ": " + cause.getMessage(), event);
                } else if (value instanceof SettlementEvent event) {
                    // Store the VA-native command so the retry path re-drives the
                    // handler directly, same as handler-level parks.
                    exceptions.park(SettlementExceptionSource.GATEWAY_SETTLEMENT,
                            event.envelope().eventId(), SettlementEvent.TYPE,
                            SettlementExceptionReason.UNEXPECTED_ERROR,
                            cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                            mapper.toCommand(event));
                } else {
                    // Deserialization poison or unknown type — park what we can under a
                    // synthetic id so the delivery stays visible; not retryable via API.
                    String syntheticId = record.topic() + "-" + record.partition() + "-" + record.offset();
                    exceptions.park(SettlementExceptionSource.UNKNOWN,
                            syntheticId, "unknown",
                            SettlementExceptionReason.UNEXPECTED_ERROR,
                            cause.getClass().getSimpleName() + ": " + cause.getMessage(),
                            Map.of("raw", String.valueOf(value)));
                }
            } catch (Exception parkFailure) {
                // Last resort: parking itself failed (e.g. DB down). Throwing makes the
                // error handler re-seek and retry the whole recovery, which is what we
                // want — the offset must not advance past an unparked event.
                log.error("Failed to park unrecoverable settlement event topic={} offset={}: {}",
                        record.topic(), record.offset(), parkFailure.getMessage());
                throw new IllegalStateException("Settlement event parking failed", parkFailure);
            }
        };

        // Transient errors: 1s → 2s → 4s → 8s, then recover (park).
        ExponentialBackOff backOff = new ExponentialBackOff(1000L, 2.0);
        backOff.setMaxAttempts(4);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        // Business failures produce identical outcomes on every retry — the handlers
        // park them directly, but if one escapes, skip retries and park immediately.
        handler.addNotRetryableExceptions(BusinessException.class, IllegalStateException.class,
                IllegalArgumentException.class);
        return handler;
    }
}
