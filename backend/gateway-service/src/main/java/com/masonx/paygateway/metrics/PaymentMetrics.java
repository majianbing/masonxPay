package com.masonx.paygateway.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Centralised metric definitions for Phase 2.1.
 *
 * Naming follows Micrometer conventions (dot-separated, lower-case).
 * Each metric is registered once at construction time and reused via
 * tag-specialised Counter/Timer lookups — cheap after the first call.
 */
@Component
public class PaymentMetrics {

    private static final String PROVIDER     = "provider";
    private static final String STATUS       = "status";
    private static final String FAILURE_CODE = "failure_code";

    private final MeterRegistry registry;

    public PaymentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ── payment.intent.confirmed ──────────────────────────────────────────────

    /**
     * Increments the confirmed-intent counter.
     *
     * @param provider    resolved provider name (STRIPE / SQUARE / BRAINTREE / unknown)
     * @param status      final intent status (SUCCEEDED / FAILED / REQUIRES_CAPTURE)
     * @param failureCode provider failure code, or "none" when succeeded
     */
    public void recordIntentConfirmed(String provider, String status, String failureCode) {
        Counter.builder("payment.intent.confirmed")
                .description("Number of payment intents that reached a terminal state after confirmation")
                .tag(PROVIDER, nullToUnknown(provider))
                .tag(STATUS, nullToUnknown(status))
                .tag(FAILURE_CODE, failureCode != null ? failureCode : "none")
                .register(registry)
                .increment();
    }

    // ── payment.charge.latency ────────────────────────────────────────────────

    /**
     * Records the wall-clock time spent in a single provider charge call.
     */
    public void recordChargeLatency(String provider, long durationMs) {
        Timer.builder("payment.charge.latency")
                .description("Latency of individual provider charge calls in milliseconds")
                .tag(PROVIDER, nullToUnknown(provider))
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    // ── payment.intent.failover ───────────────────────────────────────────────

    /**
     * Increments whenever the retry loop moves past the first provider attempt.
     */
    public void recordFailover(String fromProvider) {
        Counter.builder("payment.intent.failover")
                .description("Number of times the failover retry loop moved to a secondary provider")
                .tag("from_provider", nullToUnknown(fromProvider))
                .register(registry)
                .increment();
    }

    // ── payment.refund.initiated ──────────────────────────────────────────────

    public void recordRefundInitiated(String provider) {
        Counter.builder("payment.refund.initiated")
                .description("Number of refund requests submitted to a provider")
                .tag(PROVIDER, nullToUnknown(provider))
                .register(registry)
                .increment();
    }

    // ── payment.capture.attempted ─────────────────────────────────────────────

    public void recordCaptureAttempted(String provider, boolean succeeded) {
        Counter.builder("payment.capture.attempted")
                .description("Number of manual-capture calls to a provider")
                .tag(PROVIDER, nullToUnknown(provider))
                .tag(STATUS, succeeded ? "SUCCEEDED" : "FAILED")
                .register(registry)
                .increment();
    }

    // ── payment.webhook.outbox.processed ─────────────────────────────────────

    /**
     * Increments once per OutboxEvent successfully processed (GatewayEvent + deliveries created).
     */
    public void recordOutboxProcessed() {
        Counter.builder("payment.webhook.outbox.processed")
                .description("Number of outbox events fanned out to webhook delivery rows")
                .register(registry)
                .increment();
    }

    // ── payment.kafka.outbox.* ───────────────────────────────────────────────

    public void recordKafkaOutboxPublished(String eventType) {
        Counter.builder("payment.kafka.outbox.published")
                .description("Number of outbox events successfully published to Kafka")
                .tag("event_type", nullToUnknown(eventType))
                .register(registry)
                .increment();
    }

    public void recordKafkaOutboxFailed() {
        Counter.builder("payment.kafka.outbox.failed")
                .description("Number of Kafka outbox publish failures")
                .register(registry)
                .increment();
    }

    // ── payment.redis.* ─────────────────────────────────────────────────────

    public void recordRedisRateLimitAllowed(String resource) {
        Counter.builder("payment.redis.rate_limit.allowed")
                .description("Requests allowed by the Redis-backed rate limiter")
                .tag("resource", nullToUnknown(resource))
                .register(registry)
                .increment();
    }

    public void recordRedisRateLimitBlocked(String resource) {
        Counter.builder("payment.redis.rate_limit.blocked")
                .description("Requests blocked by the Redis-backed rate limiter")
                .tag("resource", nullToUnknown(resource))
                .register(registry)
                .increment();
    }

    public void recordRedisRateLimitFallback(String resource) {
        Counter.builder("payment.redis.rate_limit.fallback")
                .description("Requests allowed or rejected through Redis rate-limit fallback behavior")
                .tag("resource", nullToUnknown(resource))
                .register(registry)
                .increment();
    }

    public void recordRedisIdempotencyCacheHit() {
        Counter.builder("payment.redis.idempotency.hit")
                .description("Payment create idempotency route cache hits")
                .register(registry)
                .increment();
    }

    public void recordRedisIdempotencyCacheMiss() {
        Counter.builder("payment.redis.idempotency.miss")
                .description("Payment create idempotency route cache misses")
                .register(registry)
                .increment();
    }

    public void recordRedisIdempotencyFallback(String reason) {
        Counter.builder("payment.redis.idempotency.fallback")
                .description("Payment create idempotency cache fallback events")
                .tag("reason", nullToUnknown(reason))
                .register(registry)
                .increment();
    }

    public void recordRedisProviderHealthFallback(String operation) {
        Counter.builder("payment.redis.provider_health.fallback")
                .description("Provider health cache fallback events")
                .tag("operation", nullToUnknown(operation))
                .register(registry)
                .increment();
    }

    // ── payment.stale.resolved ────────────────────────────────────────────────

    public void recordStaleResolved(String resolvedStatus) {
        Counter.builder("payment.stale.resolved")
                .description("Number of stale PROCESSING intents resolved by the reconciliation job")
                .tag(STATUS, nullToUnknown(resolvedStatus))
                .register(registry)
                .increment();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String nullToUnknown(String value) {
        return value != null ? value : "unknown";
    }
}
