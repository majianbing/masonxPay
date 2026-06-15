package com.masonx.paygateway.health;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.projection.PaymentReadModelRepository;
import com.masonx.paygateway.domain.projection.ProjectionEventStatus;
import com.masonx.paygateway.domain.projection.ProjectionProcessedEventRepository;
import com.masonx.paygateway.redis.RedisProviderHealthCache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2.4 — Connector health gauge (compute + in-memory cache).
 *
 * Runs every 5 minutes and computes:
 *   1. Per-connector rolling 30-min success rate  → "connector.success.rate" gauge
 *      (tags: provider, account_id, label)
 *   2. Unprocessed webhook outbox event count     → "payment.outbox.queue.depth" gauge
 *   3. Unpublished Kafka outbox event count       → "payment.kafka.outbox.queue.depth" gauge
 *   4. Oldest unpublished Kafka outbox age        → "payment.kafka.outbox.oldest.age.seconds" gauge
 *   5. PROCESSING intents older than 30 min       → "payment.stale.processing.count" gauge
 *
 * Results are cached in-memory between runs. Micrometer gauge registration is idempotent —
 * re-registering the same name + tags on subsequent runs is a no-op, so new connector
 * accounts are picked up automatically as they appear.
 *
 * No new DB table. No Caffeine. Just an AtomicLong / ConcurrentHashMap that the
 * scheduled job refreshes and the gauge lambdas read from.
 */
@Service
public class ConnectorHealthService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorHealthService.class);
    private static final Duration ROLLING_WINDOW = Duration.ofMinutes(30);

    // Stale threshold matches the card threshold in StalePendingIntentJob
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(30);

    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentIntentRepository  paymentIntentRepository;
    private final OutboxEventRepository    outboxEventRepository;
    private final PaymentReadModelRepository paymentReadModelRepository;
    private final ProjectionProcessedEventRepository projectionProcessedEventRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final RedisProviderHealthCache   redisProviderHealthCache;
    private final MeterRegistry            meterRegistry;

    // In-memory cache: accountId (string) → success rate (0.0–1.0)
    // Written by the scheduled job, read by gauge supplier lambdas.
    private final ConcurrentHashMap<String, Double> successRates = new ConcurrentHashMap<>();

    // Scalar gauges updated atomically by the scheduled job
    private final AtomicLong outboxQueueDepth = new AtomicLong(0);
    private final AtomicLong kafkaOutboxQueueDepth = new AtomicLong(0);
    private final AtomicLong kafkaOutboxOldestAgeSeconds = new AtomicLong(0);
    private final AtomicLong staleProcessingCount = new AtomicLong(0);
    private final AtomicLong paymentReadModelCount = new AtomicLong(0);
    private final AtomicLong projectionFailedCount = new AtomicLong(0);
    private final AtomicLong projectionOldestFailedAgeSeconds = new AtomicLong(0);

    public ConnectorHealthService(PaymentRequestRepository paymentRequestRepository,
                                  PaymentIntentRepository paymentIntentRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  PaymentReadModelRepository paymentReadModelRepository,
                                  ProjectionProcessedEventRepository projectionProcessedEventRepository,
                                  ProviderAccountRepository providerAccountRepository,
                                  RedisProviderHealthCache redisProviderHealthCache,
                                  MeterRegistry meterRegistry) {
        this.paymentRequestRepository  = paymentRequestRepository;
        this.paymentIntentRepository   = paymentIntentRepository;
        this.outboxEventRepository     = outboxEventRepository;
        this.paymentReadModelRepository = paymentReadModelRepository;
        this.projectionProcessedEventRepository = projectionProcessedEventRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.redisProviderHealthCache = redisProviderHealthCache;
        this.meterRegistry             = meterRegistry;
    }

    /** Register the scalar gauges once at startup. Per-connector gauges are added as accounts appear. */
    @PostConstruct
    void registerScalarGauges() {
        Gauge.builder("payment.outbox.queue.depth", outboxQueueDepth, AtomicLong::get)
                .description("Unprocessed outbox events pending webhook delivery")
                .register(meterRegistry);

        Gauge.builder("payment.kafka.outbox.queue.depth", kafkaOutboxQueueDepth, AtomicLong::get)
                .description("Outbox events pending Kafka publication")
                .register(meterRegistry);

        Gauge.builder("payment.kafka.outbox.oldest.age.seconds", kafkaOutboxOldestAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest outbox event pending Kafka publication")
                .register(meterRegistry);

        Gauge.builder("payment.stale.processing.count", staleProcessingCount, AtomicLong::get)
                .description("PROCESSING payment intents older than 30 minutes")
                .register(meterRegistry);

        Gauge.builder("payment.projection.read_model.count", paymentReadModelCount, AtomicLong::get)
                .description("Rows in the payment read model projection")
                .register(meterRegistry);

        Gauge.builder("payment.projection.failed.count", projectionFailedCount, AtomicLong::get)
                .description("Projection events recorded as failed")
                .register(meterRegistry);

        Gauge.builder("payment.projection.oldest_failed.age.seconds", projectionOldestFailedAgeSeconds, AtomicLong::get)
                .description("Age in seconds of the oldest failed projection event")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            refreshSuccessRates();
            outboxQueueDepth.set(outboxEventRepository.countByPublishedFalse());
            kafkaOutboxQueueDepth.set(outboxEventRepository.countByKafkaPublishedFalse());
            kafkaOutboxOldestAgeSeconds.set(oldestKafkaOutboxAgeSeconds());
            staleProcessingCount.set(paymentIntentRepository.countStaleProcessing(
                    Instant.now().minus(STALE_THRESHOLD)));
            paymentReadModelCount.set(paymentReadModelRepository.count());
            projectionFailedCount.set(projectionProcessedEventRepository.countByStatus(ProjectionEventStatus.FAILED));
            projectionOldestFailedAgeSeconds.set(oldestFailedProjectionAgeSeconds());
        } catch (Exception e) {
            log.warn("ConnectorHealthService refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Returns the last-computed rolling 30-min success rate for a connector account.
     * Defaults to 1.0 (fully healthy) when no data exists yet — new connectors are
     * treated as healthy until proven otherwise.
     */
    public double getSuccessRate(UUID accountId) {
        OptionalDouble cached = redisProviderHealthCache.get(accountId);
        if (cached.isPresent()) {
            return cached.getAsDouble();
        }
        return successRates.getOrDefault(accountId.toString(), 1.0);
    }

    private void refreshSuccessRates() {
        Instant since = Instant.now().minus(ROLLING_WINDOW);
        List<Object[]> rows = paymentRequestRepository.computeConnectorSuccessRates(since);

        // Register every active account with a healthy default so new simulator
        // or real PSP connectors appear before they have rolling payment data.
        Map<UUID, ProviderAccount> accountMap = new ConcurrentHashMap<>();
        providerAccountRepository.findAllByStatus(ProviderAccountStatus.ACTIVE)
                .forEach(account -> {
                    accountMap.put(account.getId(), account);
                    registerSuccessRateGauge(account.getId(), account, 1.0);
                });

        for (Object[] row : rows) {
            UUID accountId = (UUID) row[0];
            long total     = ((Number) row[1]).longValue();
            long succeeded = ((Number) row[2]).longValue();
            double rate    = total > 0 ? (double) succeeded / total : 1.0;

            registerSuccessRateGauge(accountId, accountMap.get(accountId), rate);
        }
    }

    private void registerSuccessRateGauge(UUID accountId, ProviderAccount account, double rate) {
        String key = accountId.toString();
        successRates.put(key, rate);
        redisProviderHealthCache.put(accountId, rate);

        String provider = account != null ? account.getProvider().name() : "unknown";
        String label    = account != null ? account.getLabel()           : key;

        // Idempotent — Micrometer returns the existing meter if name+tags already registered
        Gauge.builder("connector.success.rate", successRates, m -> m.getOrDefault(key, 1.0))
                .description("Rolling 30-min payment success rate for a connector account")
                .tag("provider",   provider)
                .tag("account_id", key)
                .tag("label",      label)
                .register(meterRegistry);
    }

    private long oldestKafkaOutboxAgeSeconds() {
        Instant now = Instant.now();
        return outboxEventRepository.findFirstByKafkaPublishedFalseOrderByCreatedAtAsc()
                .map(event -> Duration.between(event.getCreatedAt(), now).toSeconds())
                .orElse(0L);
    }

    private long oldestFailedProjectionAgeSeconds() {
        Instant now = Instant.now();
        return projectionProcessedEventRepository.findFirstByStatusOrderByProcessedAtAsc(ProjectionEventStatus.FAILED)
                .map(event -> Duration.between(event.getProcessedAt(), now).toSeconds())
                .orElse(0L);
    }
}
