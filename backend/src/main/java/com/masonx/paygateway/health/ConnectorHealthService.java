package com.masonx.paygateway.health;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2.4 — Connector health gauge (compute + in-memory cache).
 *
 * Runs every 5 minutes and computes:
 *   1. Per-connector rolling 30-min success rate  → "connector.success.rate" gauge
 *      (tags: provider, account_id, label)
 *   2. Unprocessed outbox event count             → "payment.outbox.queue.depth" gauge
 *   3. PROCESSING intents older than 30 min       → "payment.stale.processing.count" gauge
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
    private final ProviderAccountRepository providerAccountRepository;
    private final MeterRegistry            meterRegistry;

    // In-memory cache: accountId (string) → success rate (0.0–1.0)
    // Written by the scheduled job, read by gauge supplier lambdas.
    private final ConcurrentHashMap<String, Double> successRates = new ConcurrentHashMap<>();

    // Scalar gauges updated atomically by the scheduled job
    private final AtomicLong outboxQueueDepth    = new AtomicLong(0);
    private final AtomicLong staleProcessingCount = new AtomicLong(0);

    public ConnectorHealthService(PaymentRequestRepository paymentRequestRepository,
                                  PaymentIntentRepository paymentIntentRepository,
                                  OutboxEventRepository outboxEventRepository,
                                  ProviderAccountRepository providerAccountRepository,
                                  MeterRegistry meterRegistry) {
        this.paymentRequestRepository  = paymentRequestRepository;
        this.paymentIntentRepository   = paymentIntentRepository;
        this.outboxEventRepository     = outboxEventRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.meterRegistry             = meterRegistry;
    }

    /** Register the scalar gauges once at startup. Per-connector gauges are added as accounts appear. */
    @PostConstruct
    void registerScalarGauges() {
        Gauge.builder("payment.outbox.queue.depth", outboxQueueDepth, AtomicLong::get)
                .description("Unprocessed outbox events pending webhook delivery")
                .register(meterRegistry);

        Gauge.builder("payment.stale.processing.count", staleProcessingCount, AtomicLong::get)
                .description("PROCESSING payment intents older than 30 minutes")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            refreshSuccessRates();
            outboxQueueDepth.set(outboxEventRepository.countByPublishedFalse());
            staleProcessingCount.set(paymentIntentRepository.countStaleProcessing(
                    Instant.now().minus(STALE_THRESHOLD)));
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
        return successRates.getOrDefault(accountId.toString(), 1.0);
    }

    private void refreshSuccessRates() {
        Instant since = Instant.now().minus(ROLLING_WINDOW);
        List<Object[]> rows = paymentRequestRepository.computeConnectorSuccessRates(since);

        // Build a lookup of accountId → ProviderAccount for tagging gauges
        Map<UUID, ProviderAccount> accountMap = new ConcurrentHashMap<>();
        providerAccountRepository.findAllByStatus(ProviderAccountStatus.ACTIVE)
                .forEach(a -> accountMap.put(a.getId(), a));

        for (Object[] row : rows) {
            UUID accountId = (UUID) row[0];
            long total     = ((Number) row[1]).longValue();
            long succeeded = ((Number) row[2]).longValue();
            double rate    = total > 0 ? (double) succeeded / total : 1.0;

            String key = accountId.toString();
            successRates.put(key, rate);

            ProviderAccount account = accountMap.get(accountId);
            String provider = account != null ? account.getProvider().name() : "unknown";
            String label    = account != null ? account.getLabel()           : accountId.toString();

            // Idempotent — Micrometer returns the existing meter if name+tags already registered
            Gauge.builder("connector.success.rate", successRates, m -> m.getOrDefault(key, 1.0))
                    .description("Rolling 30-min payment success rate for a connector account")
                    .tag("provider",   provider)
                    .tag("account_id", key)
                    .tag("label",      label)
                    .register(meterRegistry);
        }
    }
}
