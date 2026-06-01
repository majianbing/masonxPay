package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttemptRepository;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.web.dto.InvoicePaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class InvoiceBillingWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-02T10:00:00Z");

    private InvoiceRepository invoiceRepository;
    private InvoicePaymentAttemptRepository attemptRepository;
    private InvoicePaymentService invoicePaymentService;
    private SubscriptionRepository subscriptionRepository;
    private OutboxEventRepository outboxEventRepository;
    private InvoiceBillingWorker worker;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        attemptRepository = mock(InvoicePaymentAttemptRepository.class);
        invoicePaymentService = mock(InvoicePaymentService.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);

        worker = new InvoiceBillingWorker(
                invoiceRepository, attemptRepository, invoicePaymentService,
                subscriptionRepository, outboxEventRepository,
                noopTxManager(), Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(worker, "enabled", true);
        ReflectionTestUtils.setField(worker, "batchSize", 10);
        ReflectionTestUtils.setField(worker, "maxAttempts", 3);
    }

    @Test
    void processDueInvoices_success_invoicePaid() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = invoice(merchantId, invoiceId, UUID.randomUUID(), InvoiceStatus.OPEN);

        when(invoiceRepository.findDueForBilling(any(), any(Pageable.class))).thenReturn(List.of(invoice));
        when(invoiceRepository.claimForBilling(eq(invoiceId), any(), any())).thenReturn(1);
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "PAID", "ACTIVE",
                        UUID.randomUUID(), 1, true, null, null));

        worker.processDueInvoices();

        verify(invoicePaymentService).pay(merchantId, invoiceId);
        verify(invoiceRepository, never()).save(invoice);
    }

    @Test
    void processDueInvoices_retryableFailure_reschedulesForNextAttempt() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = invoice(merchantId, invoiceId, UUID.randomUUID(), InvoiceStatus.OPEN);

        when(invoiceRepository.findDueForBilling(any(), any(Pageable.class))).thenReturn(List.of(invoice));
        when(invoiceRepository.claimForBilling(eq(invoiceId), any(), any())).thenReturn(1);
        when(invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)).thenReturn(Optional.of(invoice));
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "OPEN", "PAST_DUE",
                        UUID.randomUUID(), 1, false, "insufficient_funds", "Insufficient funds"));

        worker.processDueInvoices();

        verify(invoiceRepository).findByIdAndMerchantId(invoiceId, merchantId);
        assertThat(invoice.getNextPaymentAttemptAt()).isAfter(NOW.plusSeconds(259000));
    }

    @Test
    void processDueInvoices_hardDecline_appliesFinalActionImmediately() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice invoice = invoice(merchantId, invoiceId, subscriptionId, InvoiceStatus.OPEN);
        Subscription sub = subscription(merchantId, subscriptionId, SubscriptionStatus.PAST_DUE);

        when(invoiceRepository.findDueForBilling(any(), any(Pageable.class))).thenReturn(List.of(invoice));
        when(invoiceRepository.claimForBilling(eq(invoiceId), any(), any())).thenReturn(1);
        when(invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)).thenReturn(Optional.of(invoice));
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(sub));
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "OPEN", "PAST_DUE",
                        UUID.randomUUID(), 1, false, "HARD_DECLINE", "Hard decline"));

        worker.processDueInvoices();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.UNCOLLECTIBLE);
        verify(outboxEventRepository).save(any());
    }

    @Test
    void processDueInvoices_maxAttemptsReached_appliesFinalAction() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Invoice invoice = invoice(merchantId, invoiceId, subscriptionId, InvoiceStatus.OPEN);
        Subscription sub = subscription(merchantId, subscriptionId, SubscriptionStatus.PAST_DUE);

        when(invoiceRepository.findDueForBilling(any(), any(Pageable.class))).thenReturn(List.of(invoice));
        when(invoiceRepository.claimForBilling(eq(invoiceId), any(), any())).thenReturn(1);
        when(invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)).thenReturn(Optional.of(invoice));
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(sub));
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "OPEN", "PAST_DUE",
                        UUID.randomUUID(), 3, false, "PROVIDER_ERROR", "Network error"));

        worker.processDueInvoices();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.UNCOLLECTIBLE);
        verify(outboxEventRepository).save(any());
    }

    @Test
    void processDueInvoices_concurrentClaim_skipsAlreadyClaimedInvoice() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Invoice invoice = invoice(merchantId, invoiceId, UUID.randomUUID(), InvoiceStatus.OPEN);

        when(invoiceRepository.findDueForBilling(any(), any(Pageable.class))).thenReturn(List.of(invoice));
        // Another worker already claimed it — returns 0
        when(invoiceRepository.claimForBilling(eq(invoiceId), any(), any())).thenReturn(0);

        worker.processDueInvoices();

        verify(invoicePaymentService, never()).pay(any(), any());
    }

    @Test
    void processDueInvoices_disabled_doesNothing() {
        ReflectionTestUtils.setField(worker, "enabled", false);
        worker.processDueInvoices();
        verify(invoiceRepository, never()).findDueForBilling(any(), any());
    }

    @Test
    void processDueInvoices_noDueInvoices_doesNothing() {
        when(invoiceRepository.findDueForBilling(any(), any(Pageable.class))).thenReturn(List.of());
        when(invoiceRepository.saveAll(any())).thenReturn(List.of());
        worker.processDueInvoices();
        verify(invoicePaymentService, never()).pay(any(), any());
    }

    private Invoice invoice(UUID merchantId, UUID id, UUID subscriptionId, InvoiceStatus status) {
        Invoice inv = new Invoice();
        ReflectionTestUtils.setField(inv, "id", id);
        inv.setMerchantId(merchantId);
        inv.setCustomerId(UUID.randomUUID());
        inv.setSubscriptionId(subscriptionId);
        inv.setMode(ApiKeyMode.TEST);
        inv.setStatus(status);
        inv.setAmountDue(2900);
        inv.setAmountPaid(0);
        inv.setCurrency("usd");
        inv.setPeriodStart(NOW.minusSeconds(86400));
        inv.setPeriodEnd(NOW);
        inv.setNextPaymentAttemptAt(NOW.minusSeconds(60));
        return inv;
    }

    private Subscription subscription(UUID merchantId, UUID id, SubscriptionStatus status) {
        Subscription sub = new Subscription();
        ReflectionTestUtils.setField(sub, "id", id);
        sub.setMerchantId(merchantId);
        sub.setStatus(status);
        return sub;
    }

    private PlatformTransactionManager noopTxManager() {
        return new AbstractPlatformTransactionManager() {
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object t, TransactionDefinition d) {}
            @Override protected void doCommit(DefaultTransactionStatus s) {}
            @Override protected void doRollback(DefaultTransactionStatus s) {}
        };
    }
}
