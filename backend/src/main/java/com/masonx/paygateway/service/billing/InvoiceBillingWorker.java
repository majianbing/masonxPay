package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttemptRepository;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.service.ProviderFailureCodeMapper;
import com.masonx.paygateway.web.dto.InvoicePaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Scans for due OPEN invoices and charges them off-session.
 *
 * Idempotency guarantees:
 *  1. SKIP LOCKED — concurrent worker instances claim separate invoices, no double-pickup.
 *  2. Claim window — sets nextPaymentAttemptAt = now + 1h before the provider call;
 *     a crash leaves the invoice unclaimed after 1h and the next poll retries it.
 *  3. Deterministic provider idempotency key in InvoicePaymentService (attempt-number based).
 *  4. Invoice status check in InvoicePaymentService — already-PAID invoices are no-ops.
 */
@Service
public class InvoiceBillingWorker {

    private static final Logger log = LoggerFactory.getLogger(InvoiceBillingWorker.class);

    // Default retry delays after a failed attempt: 3 days, 5 days, 7 days
    private static final List<Long> RETRY_DELAY_SECONDS = List.of(259200L, 432000L, 604800L);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    // How long a claimed invoice is held before it becomes eligible again on crash
    private static final Duration CLAIM_WINDOW = Duration.ofHours(1);

    private final InvoiceRepository invoiceRepository;
    private final InvoicePaymentAttemptRepository attemptRepository;
    private final InvoicePaymentService invoicePaymentService;
    private final SubscriptionRepository subscriptionRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    @Value("${app.billing.worker-enabled:true}")
    private boolean enabled;

    @Value("${app.billing.worker-batch-size:50}")
    private int batchSize;

    @Value("${app.billing.worker-max-attempts:3}")
    private int maxAttempts;

    @Autowired
    public InvoiceBillingWorker(InvoiceRepository invoiceRepository,
                                InvoicePaymentAttemptRepository attemptRepository,
                                InvoicePaymentService invoicePaymentService,
                                SubscriptionRepository subscriptionRepository,
                                OutboxEventRepository outboxEventRepository,
                                PlatformTransactionManager txManager) {
        this(invoiceRepository, attemptRepository, invoicePaymentService,
                subscriptionRepository, outboxEventRepository, txManager, Clock.systemUTC());
    }

    InvoiceBillingWorker(InvoiceRepository invoiceRepository,
                         InvoicePaymentAttemptRepository attemptRepository,
                         InvoicePaymentService invoicePaymentService,
                         SubscriptionRepository subscriptionRepository,
                         OutboxEventRepository outboxEventRepository,
                         PlatformTransactionManager txManager,
                         Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.attemptRepository = attemptRepository;
        this.invoicePaymentService = invoicePaymentService;
        this.subscriptionRepository = subscriptionRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.txTemplate = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.billing.worker-poll-ms:300000}")
    public void processDueInvoices() {
        if (!enabled) return;
        Instant now = Instant.now(clock);

        // Find candidates — plain SELECT, ShardingSphere-compatible.
        List<Invoice> candidates = invoiceRepository.findDueForBilling(now, PageRequest.of(0, batchSize));
        if (candidates.isEmpty()) return;
        log.debug("InvoiceBillingWorker: {} candidates, attempting optimistic claim", candidates.size());

        for (Invoice invoice : candidates) {
            // Optimistic claim: only one worker wins when both read the same invoice.
            // claimForBilling succeeds (returns 1) only if nextPaymentAttemptAt hasn't changed.
            Integer claimed = txTemplate.execute(ts ->
                    invoiceRepository.claimForBilling(invoice.getId(),
                            invoice.getNextPaymentAttemptAt(), now.plus(CLAIM_WINDOW)));
            if (claimed == null || claimed == 0) {
                log.debug("Invoice {} already claimed by another worker — skipping", invoice.getId());
                continue;
            }
            try {
                chargeInvoice(invoice);
            } catch (Exception e) {
                log.warn("Invoice billing failed for {}: {}", invoice.getId(), e.getMessage());
            }
        }
    }

    private void chargeInvoice(Invoice invoice) {
        InvoicePaymentResponse result = invoicePaymentService.pay(invoice.getMerchantId(), invoice.getId());

        if (result.success()) {
            log.info("Invoice {} paid successfully (attempt {})", invoice.getId(), result.attemptNumber());
            return;
        }

        int attemptNumber = result.attemptNumber();
        String failureCode = result.failureCode();
        boolean hard = isHardDecline(failureCode);

        // Exhausted or hard decline → apply final action
        int configuredMax = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
        if (hard || attemptNumber >= configuredMax) {
            applyFinalAction(invoice);
            return;
        }

        // Reschedule: pick delay for this attempt index, capped at last defined delay
        int idx = Math.max(0, Math.min(attemptNumber - 1, RETRY_DELAY_SECONDS.size() - 1));
        Instant nextAttempt = Instant.now(clock).plusSeconds(RETRY_DELAY_SECONDS.get(idx));
        txTemplate.executeWithoutResult(ts ->
                invoiceRepository.findByIdAndMerchantId(invoice.getId(), invoice.getMerchantId())
                        .ifPresent(inv -> {
                            inv.setNextPaymentAttemptAt(nextAttempt);
                            invoiceRepository.save(inv);
                        }));
        log.info("Invoice {} attempt {} failed ({}); next attempt at {}", invoice.getId(),
                attemptNumber, failureCode, nextAttempt);
    }

    private void applyFinalAction(Invoice invoice) {
        // Default final action: leave subscription PAST_DUE, mark invoice UNCOLLECTIBLE after max attempts.
        // Future: make final action configurable per subscription.
        txTemplate.executeWithoutResult(ts -> {
            invoiceRepository.findByIdAndMerchantId(invoice.getId(), invoice.getMerchantId())
                    .filter(inv -> inv.getStatus() == InvoiceStatus.OPEN)
                    .ifPresent(inv -> {
                        inv.setStatus(InvoiceStatus.UNCOLLECTIBLE);
                        inv.setNextPaymentAttemptAt(null);
                        invoiceRepository.save(inv);
                    });
            subscriptionRepository.findByIdAndMerchantId(invoice.getSubscriptionId(), invoice.getMerchantId())
                    .ifPresent(sub -> {
                        if (sub.getStatus() == SubscriptionStatus.PAST_DUE) {
                            // Leave PAST_DUE — merchant can take action (update method, cancel manually)
                            outboxEventRepository.save(new OutboxEvent(
                                    invoice.getMerchantId(), "invoice.uncollectible", invoice.getId(),
                                    "{\"invoiceId\":\"" + invoice.getId()
                                            + "\",\"subscriptionId\":\"" + invoice.getSubscriptionId() + "\"}"));
                        }
                    });
        });
        log.info("Invoice {} marked UNCOLLECTIBLE after retry exhaustion", invoice.getId());
    }

    private static boolean isHardDecline(String failureCode) {
        if (failureCode == null) return false;
        String code = failureCode.toLowerCase();
        return code.equals(ProviderFailureCodeMapper.HARD_DECLINE.toLowerCase())
                || code.equals(ProviderFailureCodeMapper.RISK_DECLINE.toLowerCase())
                || code.equals(ProviderFailureCodeMapper.INVALID_PAYMENT_METHOD.toLowerCase())
                || code.contains("requires_customer_action")
                || code.contains("connector_not_configured");
    }
}
