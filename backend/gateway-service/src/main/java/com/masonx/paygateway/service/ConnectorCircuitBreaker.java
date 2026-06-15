package com.masonx.paygateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory per-connector-account circuit breaker.
 *
 * Opens after {@value FAILURE_THRESHOLD} consecutive retryable failures, stays open for
 * {@value OPEN_DURATION_SECONDS}s, then auto-closes (half-open: the next routing attempt
 * is allowed through as a probe — if it succeeds the counter resets, if it fails the
 * circuit opens again).
 *
 * Hard declines (card_declined, insufficient_funds, etc.) do NOT count toward the
 * error threshold — the problem is the card, not the connector.
 *
 * Thread-safe: all state is in ConcurrentHashMap + AtomicInteger.
 * No persistence needed — a restarted node starts with all circuits closed, which is
 * the correct safe default (the routing layer re-opens them quickly if the connector
 * is still degraded).
 */
@Component
public class ConnectorCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCircuitBreaker.class);

    static final int      FAILURE_THRESHOLD    = 3;
    static final Duration OPEN_DURATION        = Duration.ofSeconds(30);

    // Consecutive retryable error count per account — reset to 0 on success
    private final ConcurrentHashMap<UUID, AtomicInteger> consecutiveErrors = new ConcurrentHashMap<>();
    // When the circuit for an account was opened (null → closed)
    private final ConcurrentHashMap<UUID, Instant>       circuitOpenUntil  = new ConcurrentHashMap<>();

    /**
     * Returns {@code true} if the circuit for this account is open (i.e. skip it when routing).
     * Automatically expires the open state after {@value OPEN_DURATION_SECONDS}s.
     */
    public boolean isOpen(UUID accountId) {
        Instant until = circuitOpenUntil.get(accountId);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            // Cooldown expired — auto-close and allow a probe attempt through
            circuitOpenUntil.remove(accountId);
            consecutiveErrors.remove(accountId);
            return false;
        }
        return true;
    }

    /**
     * Records a successful charge — resets the error counter and closes the circuit.
     * Must be called after every successful charge, including probe attempts after cooldown.
     */
    public void recordSuccess(UUID accountId) {
        consecutiveErrors.remove(accountId);
        Instant removed = circuitOpenUntil.remove(accountId);
        if (removed != null) {
            log.info("Circuit CLOSED for connector {} — successful charge", accountId);
        }
    }

    /**
     * Records a failed charge attempt.
     *
     * @param accountId  the connector account that was attempted
     * @param retryable  true if the failure was a transient/technical error (worth retrying
     *                   a different connector); false for hard card declines
     */
    public void recordFailure(UUID accountId, boolean retryable) {
        if (!retryable) {
            // Hard decline — the card is the problem, not the connector.
            // Reset the error counter so one bad card can't open the circuit.
            consecutiveErrors.remove(accountId);
            return;
        }

        int count = consecutiveErrors
                .computeIfAbsent(accountId, k -> new AtomicInteger(0))
                .incrementAndGet();

        if (count >= FAILURE_THRESHOLD) {
            Instant openUntil = Instant.now().plus(OPEN_DURATION);
            circuitOpenUntil.put(accountId, openUntil);
            log.warn("Circuit OPENED for connector {} after {} consecutive retryable failures — "
                    + "routing will skip this account until {}", accountId, count, openUntil);
        }
    }
}
