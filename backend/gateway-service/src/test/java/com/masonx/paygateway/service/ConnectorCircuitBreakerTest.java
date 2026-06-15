package com.masonx.paygateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorCircuitBreakerTest {

    private ConnectorCircuitBreaker cb;
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cb = new ConnectorCircuitBreaker();
    }

    @Test
    void newBreaker_circuitClosed() {
        assertThat(cb.isOpen(accountId)).isFalse();
    }

    @Test
    void retryableFailures_belowThreshold_circuitStillClosed() {
        for (int i = 0; i < ConnectorCircuitBreaker.FAILURE_THRESHOLD - 1; i++) {
            cb.recordFailure(accountId, true);
        }
        assertThat(cb.isOpen(accountId)).isFalse();
    }

    @Test
    void retryableFailures_atThreshold_opensCircuit() {
        for (int i = 0; i < ConnectorCircuitBreaker.FAILURE_THRESHOLD; i++) {
            cb.recordFailure(accountId, true);
        }
        assertThat(cb.isOpen(accountId)).isTrue();
    }

    @Test
    void hardDeclineFailures_neverOpenCircuit() {
        for (int i = 0; i < ConnectorCircuitBreaker.FAILURE_THRESHOLD + 5; i++) {
            cb.recordFailure(accountId, false); // hard decline — not retryable
        }
        assertThat(cb.isOpen(accountId)).isFalse();
    }

    @Test
    void successAfterFailures_closesCircuitAndResetsCounter() {
        // Open the circuit
        for (int i = 0; i < ConnectorCircuitBreaker.FAILURE_THRESHOLD; i++) {
            cb.recordFailure(accountId, true);
        }
        assertThat(cb.isOpen(accountId)).isTrue();

        // Record success — should close
        cb.recordSuccess(accountId);
        assertThat(cb.isOpen(accountId)).isFalse();

        // Counter must be reset — need FAILURE_THRESHOLD new failures to re-open
        for (int i = 0; i < ConnectorCircuitBreaker.FAILURE_THRESHOLD - 1; i++) {
            cb.recordFailure(accountId, true);
        }
        assertThat(cb.isOpen(accountId)).isFalse();
    }

    @Test
    void expiredCooldown_autoClosesCircuit() {
        // Manually set the circuitOpenUntil to a past time to simulate cooldown expiry
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<UUID, Instant> openUntil =
                (ConcurrentHashMap<UUID, Instant>) ReflectionTestUtils.getField(cb, "circuitOpenUntil");
        assert openUntil != null;
        openUntil.put(accountId, Instant.now().minusSeconds(60)); // already past

        // isOpen should auto-expire and return false
        assertThat(cb.isOpen(accountId)).isFalse();
        // And the map should now be empty
        assertThat(openUntil).doesNotContainKey(accountId);
    }

    @Test
    void hardDeclineAfterRetryableErrors_resetsCounter() {
        // Two retryable failures — approaching threshold
        cb.recordFailure(accountId, true);
        cb.recordFailure(accountId, true);

        // Hard decline resets the counter (the card is the problem, not the connector)
        cb.recordFailure(accountId, false);

        // Should take FAILURE_THRESHOLD more retryable failures to open, not just 1 more
        cb.recordFailure(accountId, true);
        assertThat(cb.isOpen(accountId)).isFalse();
    }

    @Test
    void independentAccountIds_doNotAffectEachOther() {
        UUID other = UUID.randomUUID();

        for (int i = 0; i < ConnectorCircuitBreaker.FAILURE_THRESHOLD; i++) {
            cb.recordFailure(accountId, true);
        }

        assertThat(cb.isOpen(accountId)).isTrue();
        assertThat(cb.isOpen(other)).isFalse(); // other account unaffected
    }
}
