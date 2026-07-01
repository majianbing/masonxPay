package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.service.GatewayIdService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Advances subscription billing periods that have passed their end date.
 * For ACTIVE subscriptions: advances the period window and generates a new invoice.
 * Honors cancel_at_period_end by transitioning to CANCELED instead of advancing.
 * Period advance and invoice creation are atomic — both succeed or neither does.
 */
@Service
public class SubscriptionPeriodAdvancementService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionPeriodAdvancementService.class);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final InvoiceRepository invoiceRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final GatewayIdService gatewayIdService;

    @Value("${app.billing.advancement-enabled:true}")
    private boolean enabled;

    @Value("${app.billing.advancement-batch-size:50}")
    private int batchSize;

    @org.springframework.beans.factory.annotation.Autowired
    public SubscriptionPeriodAdvancementService(SubscriptionRepository subscriptionRepository,
                                                SubscriptionItemRepository itemRepository,
                                                InvoiceRepository invoiceRepository,
                                                OutboxEventRepository outboxEventRepository,
                                                PlatformTransactionManager txManager,
                                                GatewayIdService gatewayIdService) {
        this(subscriptionRepository, itemRepository, invoiceRepository, outboxEventRepository,
                txManager, Clock.systemUTC(), gatewayIdService);
    }

    SubscriptionPeriodAdvancementService(SubscriptionRepository subscriptionRepository,
                                         SubscriptionItemRepository itemRepository,
                                         InvoiceRepository invoiceRepository,
                                         OutboxEventRepository outboxEventRepository,
                                         PlatformTransactionManager txManager,
                                         Clock clock) {
        this(subscriptionRepository, itemRepository, invoiceRepository, outboxEventRepository,
                txManager, clock, null);
    }

    SubscriptionPeriodAdvancementService(SubscriptionRepository subscriptionRepository,
                                         SubscriptionItemRepository itemRepository,
                                         InvoiceRepository invoiceRepository,
                                         OutboxEventRepository outboxEventRepository,
                                         PlatformTransactionManager txManager,
                                         Clock clock,
                                         GatewayIdService gatewayIdService) {
        this.subscriptionRepository = subscriptionRepository;
        this.itemRepository = itemRepository;
        this.invoiceRepository = invoiceRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.txTemplate = new TransactionTemplate(txManager);
        this.clock = clock;
        this.gatewayIdService = gatewayIdService;
    }

    @Scheduled(fixedDelayString = "${app.billing.advancement-poll-ms:3600000}")
    public void advanceOverduePeriods() {
        if (!enabled) return;
        Instant now = Instant.now(clock);
        List<Subscription> due = subscriptionRepository.findByStatusAndCurrentPeriodEndBefore(
                SubscriptionStatus.ACTIVE, now, PageRequest.of(0, batchSize));
        for (Subscription subscription : due) {
            try {
                advanceOne(subscription.getMerchantId(), subscription.getId());
            } catch (Exception e) {
                log.warn("Period advancement failed for subscription {}: {}", subscription.getId(), e.getMessage());
            }
        }
    }

    void advanceOne(UUID merchantId, UUID subscriptionId) {
        txTemplate.executeWithoutResult(ts -> {
            Subscription sub = subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)
                    .orElse(null);
            if (sub == null || sub.getStatus() != SubscriptionStatus.ACTIVE) return;
            if (sub.getCurrentPeriodEnd() == null || !sub.getCurrentPeriodEnd().isBefore(Instant.now(clock))) return;

            if (sub.isCancelAtPeriodEnd()) {
                sub.setStatus(SubscriptionStatus.CANCELED);
                sub.setCanceledAt(Instant.now(clock));
                subscriptionRepository.save(sub);
                OutboxEvent event = new OutboxEvent(
                        sub.getMerchantId(), "subscription.canceled", sub.getId(),
                        "{\"subscriptionId\":\"" + sub.getId() + "\",\"reason\":\"cancel_at_period_end\"}");
                if (gatewayIdService != null) {
                    gatewayIdService.assignOutboxEvent(event);
                }
                outboxEventRepository.save(event);
                log.info("Subscription {} canceled at period end", sub.getId());
            } else {
                Instant newStart = sub.getCurrentPeriodEnd();
                Instant newEnd = nextPeriodEnd(newStart, sub.getIntervalUnit(), sub.getIntervalCount());
                sub.setCurrentPeriodStart(newStart);
                sub.setCurrentPeriodEnd(newEnd);
                subscriptionRepository.save(sub);
                generateInvoice(sub, newStart, newEnd);
                log.info("Subscription {} advanced to period {}/{}", sub.getId(), newStart, newEnd);
            }
        });
    }

    private void generateInvoice(Subscription sub, Instant periodStart, Instant periodEnd) {
        // Idempotent: skip if an invoice for this period already exists.
        if (invoiceRepository.findByMerchantIdAndSubscriptionIdAndPeriodStartAndPeriodEnd(
                sub.getMerchantId(), sub.getId(), periodStart, periodEnd).isPresent()) {
            return;
        }
        long amountDue = itemRepository
                .findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(sub.getMerchantId(), sub.getId())
                .stream()
                .mapToLong(item -> item.getAmount() * item.getQuantity())
                .sum();
        if (amountDue <= 0) {
            log.warn("Subscription {} has no billable items; skipping invoice generation", sub.getId());
            return;
        }
        Instant now = Instant.now(clock);
        Invoice invoice = new Invoice();
        invoice.setMerchantId(sub.getMerchantId());
        invoice.setCustomerId(sub.getCustomerId());
        invoice.setSubscriptionId(sub.getId());
        invoice.setMode(sub.getMode());
        invoice.setStatus(InvoiceStatus.OPEN);
        invoice.setAmountDue(amountDue);
        invoice.setAmountPaid(0);
        invoice.setCurrency(sub.getCurrency());
        invoice.setPeriodStart(periodStart);
        invoice.setPeriodEnd(periodEnd);
        invoice.setDueAt(now);
        invoice.setNextPaymentAttemptAt(now);
        if (gatewayIdService != null) {
            gatewayIdService.assignInvoice(invoice);
        }
        invoiceRepository.save(invoice);
    }

    static Instant nextPeriodEnd(Instant start, BillingIntervalUnit unit, int count) {
        ZonedDateTime utcStart = start.atZone(ZoneOffset.UTC);
        ZonedDateTime utcEnd = switch (unit) {
            case DAY   -> utcStart.plusDays(count);
            case WEEK  -> utcStart.plusWeeks(count);
            case MONTH -> utcStart.plusMonths(count);
            case YEAR  -> utcStart.plusYears(count);
        };
        return utcEnd.toInstant();
    }
}
