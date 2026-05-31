package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionItem;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionPeriodAdvancementServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionItemRepository itemRepository;
    private InvoiceRepository invoiceRepository;
    private OutboxEventRepository outboxEventRepository;
    private Clock clock;
    private SubscriptionPeriodAdvancementService service;

    private final Instant now = Instant.parse("2026-07-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        itemRepository = mock(SubscriptionItemRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        clock = Clock.fixed(now, ZoneOffset.UTC);
        service = new SubscriptionPeriodAdvancementService(
                subscriptionRepository, itemRepository, invoiceRepository,
                outboxEventRepository, noopTxManager(), clock);
    }

    @Test
    void advanceOne_activeSubscriptionWithOverduePeriod_advancesPeriodAndGeneratesInvoice() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Instant periodStart = now.minus(60, ChronoUnit.DAYS);
        Instant periodEnd = now.minus(30, ChronoUnit.DAYS); // overdue — 30 days in the past

        Subscription sub = subscription(merchantId, subscriptionId, SubscriptionStatus.ACTIVE,
                periodStart, periodEnd, BillingIntervalUnit.MONTH, 1, false);

        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(sub));
        when(itemRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(merchantId, subscriptionId))
                .thenReturn(List.of(item(merchantId, subscriptionId, 2900, 1)));
        when(invoiceRepository.findByMerchantIdAndSubscriptionIdAndPeriodStartAndPeriodEnd(
                any(), any(), any(), any())).thenReturn(Optional.empty());
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.advanceOne(merchantId, subscriptionId);

        // Period advanced to: old period_end → old period_end + 1 month
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(periodEnd);
        Instant expectedNewEnd = SubscriptionPeriodAdvancementService.nextPeriodEnd(
                periodEnd, BillingIntervalUnit.MONTH, 1);
        assertThat(sub.getCurrentPeriodEnd()).isEqualTo(expectedNewEnd);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        ArgumentCaptor<Invoice> invoiceCaptor = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).save(invoiceCaptor.capture());
        Invoice invoice = invoiceCaptor.getValue();
        assertThat(invoice.getMerchantId()).isEqualTo(merchantId);
        assertThat(invoice.getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(invoice.getAmountDue()).isEqualTo(2900);
        assertThat(invoice.getPeriodStart()).isEqualTo(periodEnd);
        assertThat(invoice.getPeriodEnd()).isEqualTo(expectedNewEnd);
        assertThat(invoice.getMode()).isEqualTo(ApiKeyMode.TEST);
    }

    @Test
    void advanceOne_cancelAtPeriodEnd_cancelSubscriptionInsteadOfAdvancing() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Instant periodEnd = now.minus(1, ChronoUnit.DAYS);

        Subscription sub = subscription(merchantId, subscriptionId, SubscriptionStatus.ACTIVE,
                now.minus(31, ChronoUnit.DAYS), periodEnd, BillingIntervalUnit.MONTH, 1, true);

        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(sub));

        service.advanceOne(merchantId, subscriptionId);

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(sub.getCanceledAt()).isEqualTo(now);
        verify(subscriptionRepository).save(sub);
        verify(invoiceRepository, never()).save(any());

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("subscription.canceled");
    }

    @Test
    void advanceOne_periodNotYetOverdue_skipsAdvancement() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Instant futurePeriodEnd = now.plus(10, ChronoUnit.DAYS); // still in the future

        Subscription sub = subscription(merchantId, subscriptionId, SubscriptionStatus.ACTIVE,
                now.minus(20, ChronoUnit.DAYS), futurePeriodEnd, BillingIntervalUnit.MONTH, 1, false);

        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(sub));

        service.advanceOne(merchantId, subscriptionId);

        verify(subscriptionRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void advanceOne_notActiveSubscription_skipsAdvancement() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();

        Subscription sub = subscription(merchantId, subscriptionId, SubscriptionStatus.PAST_DUE,
                now.minus(60, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS),
                BillingIntervalUnit.MONTH, 1, false);

        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(sub));

        service.advanceOne(merchantId, subscriptionId);

        verify(subscriptionRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void advanceOne_invoiceAlreadyExists_skipsInvoiceCreation() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Instant periodEnd = now.minus(1, ChronoUnit.DAYS);

        Subscription sub = subscription(merchantId, subscriptionId, SubscriptionStatus.ACTIVE,
                now.minus(31, ChronoUnit.DAYS), periodEnd, BillingIntervalUnit.MONTH, 1, false);

        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(sub));
        when(itemRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(merchantId, subscriptionId))
                .thenReturn(List.of(item(merchantId, subscriptionId, 2900, 1)));
        when(invoiceRepository.findByMerchantIdAndSubscriptionIdAndPeriodStartAndPeriodEnd(
                any(), any(), any(), any())).thenReturn(Optional.of(new Invoice()));

        service.advanceOne(merchantId, subscriptionId);

        // Period is still advanced; invoice creation is skipped (idempotent)
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(periodEnd);
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void nextPeriodEnd_month_addsCalendarMonth() {
        Instant jan31 = Instant.parse("2026-01-31T00:00:00Z");
        Instant result = SubscriptionPeriodAdvancementService.nextPeriodEnd(jan31, BillingIntervalUnit.MONTH, 1);
        assertThat(result).isEqualTo(Instant.parse("2026-02-28T00:00:00Z")); // calendar-aware
    }

    @Test
    void nextPeriodEnd_year_addsCalendarYear() {
        Instant feb29 = Instant.parse("2024-02-29T00:00:00Z"); // leap year
        Instant result = SubscriptionPeriodAdvancementService.nextPeriodEnd(feb29, BillingIntervalUnit.YEAR, 1);
        assertThat(result).isEqualTo(Instant.parse("2025-02-28T00:00:00Z")); // non-leap
    }

    private Subscription subscription(UUID merchantId, UUID subscriptionId, SubscriptionStatus status,
                                      Instant periodStart, Instant periodEnd,
                                      BillingIntervalUnit unit, int count, boolean cancelAtPeriodEnd) {
        Subscription sub = new Subscription();
        ReflectionTestUtils.setField(sub, "id", subscriptionId);
        sub.setMerchantId(merchantId);
        sub.setCustomerId(UUID.randomUUID());
        sub.setMode(ApiKeyMode.TEST);
        sub.setStatus(status);
        sub.setCurrency("usd");
        sub.setIntervalUnit(unit);
        sub.setIntervalCount(count);
        sub.setCurrentPeriodStart(periodStart);
        sub.setCurrentPeriodEnd(periodEnd);
        sub.setCancelAtPeriodEnd(cancelAtPeriodEnd);
        return sub;
    }

    private SubscriptionItem item(UUID merchantId, UUID subscriptionId, long amount, int quantity) {
        SubscriptionItem item = new SubscriptionItem();
        item.setMerchantId(merchantId);
        item.setSubscriptionId(subscriptionId);
        item.setDescription("Pro plan");
        item.setAmount(amount);
        item.setQuantity(quantity);
        return item;
    }

    private PlatformTransactionManager noopTxManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() { return new Object(); }
            @Override
            protected void doBegin(Object t, TransactionDefinition d) {}
            @Override
            protected void doCommit(DefaultTransactionStatus s) {}
            @Override
            protected void doRollback(DefaultTransactionStatus s) {}
        };
    }
}
