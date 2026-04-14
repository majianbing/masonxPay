package com.masonx.paygateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.event.PaymentGatewayEvent;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Periodic job that resolves PROCESSING payment intents that have been stuck too long
 * without a provider webhook updating their status.
 *
 * Two thresholds — chosen to match realistic settlement windows per method type:
 *   - Card payments:  30 minutes  (cards settle synchronously; stale = webhook missed or crash)
 *   - All other / unknown methods: 7 days (iDEAL, ACH, SEPA, etc. can legitimately take days)
 *
 * For each stale intent the job:
 *   1. Skips if providerPaymentId is null — never reached the provider, cancel locally.
 *   2. Calls syncStatus on the provider — if we get a definitive answer, apply it.
 *   3. If provider still says in-flight, attempt cancelAtProvider, then mark CANCELED locally.
 *
 * No @Transactional on reconcile() or reconcileOne() — remote provider calls must never
 * hold a DB connection open. Each DB write is wrapped in its own short TransactionTemplate block.
 */
@Service
public class StalePendingIntentJob {

    private static final Logger log = LoggerFactory.getLogger(StalePendingIntentJob.class);

    private static final Duration CARD_THRESHOLD  = Duration.ofMinutes(30);
    private static final Duration OTHER_THRESHOLD = Duration.ofDays(7);
    private static final int     BATCH_SIZE       = 50;

    private final PaymentIntentRepository  paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentProviderDispatcher dispatcher;
    private final ProviderAccountService   providerAccountService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper             objectMapper;
    private final TransactionTemplate      txTemplate;

    public StalePendingIntentJob(PaymentIntentRepository paymentIntentRepository,
                                 PaymentRequestRepository paymentRequestRepository,
                                 PaymentProviderDispatcher dispatcher,
                                 ProviderAccountService providerAccountService,
                                 ApplicationEventPublisher eventPublisher,
                                 ObjectMapper objectMapper,
                                 PlatformTransactionManager txManager) {
        this.paymentIntentRepository  = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.dispatcher               = dispatcher;
        this.providerAccountService   = providerAccountService;
        this.eventPublisher           = eventPublisher;
        this.objectMapper             = objectMapper;
        this.txTemplate               = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelay = 300_000) // every 5 minutes
    public void reconcile() {
        Instant cardCutoff  = Instant.now().minus(CARD_THRESHOLD);
        Instant otherCutoff = Instant.now().minus(OTHER_THRESHOLD);

        List<PaymentIntent> stale = txTemplate.execute(ts ->
                paymentIntentRepository.findStaleProcessing(cardCutoff, otherCutoff,
                        PageRequest.of(0, BATCH_SIZE)));

        if (stale == null || stale.isEmpty()) return;

        log.info("Stale intent job: {} PROCESSING intent(s) to reconcile", stale.size());

        for (PaymentIntent intent : stale) {
            try {
                reconcileOne(intent);
            } catch (Exception e) {
                log.warn("Stale intent job: failed to reconcile intent {}: {}", intent.getId(), e.getMessage());
            }
        }
    }

    private void reconcileOne(PaymentIntent intent) {
        // Never reached the provider — no charge was made, cancel locally
        if (intent.getProviderPaymentId() == null || intent.getConnectorAccountId() == null) {
            log.info("Stale intent {}: no provider contact recorded — canceling locally", intent.getId());
            applyLocalStatus(intent, PaymentIntentStatus.CANCELED);
            return;
        }

        ProviderCredentials creds;
        try {
            creds = providerAccountService.loadCredentials(intent.getConnectorAccountId());
        } catch (Exception e) {
            log.warn("Stale intent {}: cannot load credentials for connector {} — skipping: {}",
                    intent.getId(), intent.getConnectorAccountId(), e.getMessage());
            return;
        }

        // Step 1: ask the provider for the current status
        Optional<PaymentIntentStatus> synced = dispatcher.syncStatus(
                intent.getResolvedProvider(), intent.getProviderPaymentId(), creds);

        if (synced.isPresent()) {
            log.info("Stale intent {}: provider returned status {} — applying locally",
                    intent.getId(), synced.get());
            applyLocalStatus(intent, synced.get());
            return;
        }

        // Step 2: provider still says in-flight — attempt cancellation
        log.info("Stale intent {}: still in-flight at provider, attempting cancel", intent.getId());
        boolean canceledAtProvider = dispatcher.cancelAtProvider(
                intent.getResolvedProvider(), intent.getProviderPaymentId(), creds);

        if (!canceledAtProvider) {
            log.warn("Stale intent {}: provider cancel rejected or failed — still marking CANCELED locally " +
                    "(manual review may be needed at provider)", intent.getId());
        }

        applyLocalStatus(intent, PaymentIntentStatus.CANCELED);
    }

    /**
     * Writes the resolved status to the DB inside a short transaction.
     * Re-reads the intent to guard against races — if another thread already
     * moved it out of PROCESSING, this write is skipped.
     */
    private void applyLocalStatus(PaymentIntent intent, PaymentIntentStatus newStatus) {
        PaymentIntent updated = txTemplate.execute(ts -> {
            PaymentIntent fresh = paymentIntentRepository.findById(intent.getId()).orElse(null);
            if (fresh == null || fresh.getStatus() != PaymentIntentStatus.PROCESSING) return null;
            fresh.setStatus(newStatus);
            return paymentIntentRepository.save(fresh);
        });

        if (updated == null) return; // raced — already resolved by webhook or another thread

        publishEvent(updated);
    }

    private void publishEvent(PaymentIntent intent) {
        try {
            List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
            PaymentIntentResponse response = PaymentIntentResponse.from(intent, attempts, objectMapper, null);
            String json = objectMapper.writeValueAsString(response);

            String eventType = switch (intent.getStatus()) {
                case SUCCEEDED -> "payment_intent.succeeded";
                case FAILED    -> "payment_intent.failed";
                default        -> "payment_intent.canceled";
            };

            eventPublisher.publishEvent(
                    new PaymentGatewayEvent(this, intent.getMerchantId(), eventType, intent.getId(), json));
        } catch (JsonProcessingException e) {
            // non-critical — status is already persisted, event is best-effort
        }
    }
}
