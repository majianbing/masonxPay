package com.masonx.paygateway.service.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.payment.RefundRepository;
import com.masonx.paygateway.domain.retry.ScheduledRetryJob;
import com.masonx.paygateway.domain.retry.ScheduledRetryJobRepository;
import com.masonx.paygateway.domain.retry.ScheduledRetryOperation;
import com.masonx.paygateway.domain.retry.ScheduledRetryStatus;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.service.ProviderAccountService;
import com.masonx.paygateway.service.billing.InvoicePaymentService;
import com.masonx.paygateway.web.dto.InvoicePaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledRetryWorkerInvoicePaymentTest {

    private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");

    private ScheduledRetryJobRepository retryJobRepository;
    private InvoicePaymentService invoicePaymentService;
    private InvoiceRepository invoiceRepository;
    private SubscriptionRepository subscriptionRepository;
    private OutboxEventRepository outboxEventRepository;
    private ScheduledRetryWorkerService service;

    @BeforeEach
    void setUp() {
        retryJobRepository = mock(ScheduledRetryJobRepository.class);
        invoicePaymentService = mock(InvoicePaymentService.class);
        invoiceRepository = mock(InvoiceRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);

        service = new ScheduledRetryWorkerService(
                retryJobRepository,
                mock(PaymentIntentRepository.class),
                mock(PaymentRequestRepository.class),
                mock(RefundRepository.class),
                mock(PaymentProviderDispatcher.class),
                mock(ProviderAccountService.class),
                invoicePaymentService,
                invoiceRepository,
                subscriptionRepository,
                outboxEventRepository,
                new ObjectMapper().findAndRegisterModules(),
                mock(PaymentMetrics.class),
                noopTxManager(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "test-worker");
        ReflectionTestUtils.setField(service, "backoffSeconds", 900L);
    }

    @Test
    void invoicePayment_success_marksJobSucceeded() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        ScheduledRetryJob job = job(jobId, merchantId, invoiceId, 1, 3, null);
        when(retryJobRepository.findScheduledForUpdate(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(retryJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "PAID", "ACTIVE",
                        UUID.randomUUID(), 1, true, null, null));

        service.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(ScheduledRetryStatus.SUCCEEDED);
    }

    @Test
    void invoicePayment_retryableFailure_reschedulesWithPayloadDelay() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String payload = "{\"retryDelaysSeconds\":[259200,432000,604800],\"finalAction\":\"PAST_DUE\"}";

        ScheduledRetryJob job = job(jobId, merchantId, invoiceId, 1, 3, payload);
        when(retryJobRepository.findScheduledForUpdate(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(retryJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "OPEN", "PAST_DUE",
                        UUID.randomUUID(), 1, false, "card_declined", "Card declined"));

        service.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(ScheduledRetryStatus.SCHEDULED);
        // First retry delay is 259200s (3 days) — next_run_at should be ~3 days from now
        assertThat(job.getNextRunAt()).isAfter(NOW.plusSeconds(259199));
    }

    @Test
    void invoicePayment_hardDecline_terminatesWithoutReschedule() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String payload = "{\"retryDelaysSeconds\":[259200],\"finalAction\":\"PAST_DUE\"}";

        ScheduledRetryJob job = job(jobId, merchantId, invoiceId, 1, 3, payload);
        when(retryJobRepository.findScheduledForUpdate(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(retryJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "OPEN", "PAST_DUE",
                        UUID.randomUUID(), 1, false, "HARD_DECLINE", "Hard decline"));

        service.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(ScheduledRetryStatus.FAILED);
    }

    @Test
    void invoicePayment_exhaustedRetries_cancelFinalAction_cancelsSubscription() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String payload = "{\"retryDelaysSeconds\":[86400],\"finalAction\":\"CANCEL\"}";

        // Last attempt: claim will increment to 3 = maxAttempts, then applyOutcome applies final action
        ScheduledRetryJob job = job(jobId, merchantId, invoiceId, 2, 3, payload);
        Subscription sub = subscription(merchantId, subscriptionId, SubscriptionStatus.PAST_DUE);
        Invoice invoice = invoice(merchantId, invoiceId, subscriptionId);

        when(retryJobRepository.findScheduledForUpdate(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(retryJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "OPEN", "PAST_DUE",
                        UUID.randomUUID(), 3, false, "PROVIDER_ERROR", "Network error"));
        when(invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)).thenReturn(Optional.of(invoice));
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(ScheduledRetryStatus.FAILED);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.CANCELED);
        assertThat(sub.getCanceledAt()).isNotNull();
        verify(outboxEventRepository).save(any());
    }

    @Test
    void invoicePayment_exhaustedRetries_pastDueFinalAction_leavesSubscriptionAsIs() {
        UUID merchantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        String payload = "{\"retryDelaysSeconds\":[86400],\"finalAction\":\"PAST_DUE\"}";

        ScheduledRetryJob job = job(jobId, merchantId, invoiceId, 2, 3, payload);
        when(retryJobRepository.findScheduledForUpdate(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(retryJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(invoicePaymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(invoiceId, "OPEN", "PAST_DUE",
                        UUID.randomUUID(), 3, false, "PROVIDER_ERROR", "Network error"));
        when(invoiceRepository.findByIdAndMerchantId(eq(invoiceId), eq(merchantId))).thenReturn(Optional.empty());

        service.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(ScheduledRetryStatus.FAILED);
        verify(subscriptionRepository, never()).save(any());
    }

    private ScheduledRetryJob job(UUID id, UUID merchantId, UUID invoiceId,
                                   int attemptCount, int maxAttempts, String payloadJson) {
        ScheduledRetryJob job = new ScheduledRetryJob();
        ReflectionTestUtils.setField(job, "id", id);
        job.setMerchantId(merchantId);
        job.setOperation(ScheduledRetryOperation.INVOICE_PAYMENT);
        job.setStatus(ScheduledRetryStatus.SCHEDULED);
        job.setInvoiceId(invoiceId);
        job.setAttemptCount(attemptCount);
        job.setMaxAttempts(maxAttempts);
        job.setNextRunAt(NOW.minusSeconds(1));
        job.setPayloadJson(payloadJson);
        return job;
    }

    private Subscription subscription(UUID merchantId, UUID id, SubscriptionStatus status) {
        Subscription s = new Subscription();
        ReflectionTestUtils.setField(s, "id", id);
        s.setMerchantId(merchantId);
        s.setStatus(status);
        return s;
    }

    private Invoice invoice(UUID merchantId, UUID id, UUID subscriptionId) {
        Invoice inv = new Invoice();
        ReflectionTestUtils.setField(inv, "id", id);
        inv.setMerchantId(merchantId);
        inv.setSubscriptionId(subscriptionId);
        inv.setStatus(InvoiceStatus.OPEN);
        return inv;
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
