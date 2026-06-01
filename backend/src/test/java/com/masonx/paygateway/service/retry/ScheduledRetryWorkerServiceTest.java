package com.masonx.paygateway.service.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.payment.RefundRepository;
import com.masonx.paygateway.domain.retry.ScheduledRetryJob;
import com.masonx.paygateway.domain.retry.ScheduledRetryJobRepository;
import com.masonx.paygateway.domain.retry.ScheduledRetryOperation;
import com.masonx.paygateway.domain.retry.ScheduledRetryStatus;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.masonx.paygateway.service.ProviderAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledRetryWorkerServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-26T10:00:00Z");

    private ScheduledRetryJobRepository retryJobRepository;
    private PaymentIntentRepository paymentIntentRepository;
    private PaymentRequestRepository paymentRequestRepository;
    private RefundRepository refundRepository;
    private PaymentProviderDispatcher dispatcher;
    private ProviderAccountService providerAccountService;
    private OutboxEventRepository outboxEventRepository;
    private PaymentMetrics metrics;
    private ScheduledRetryWorkerService service;

    @BeforeEach
    void setUp() {
        retryJobRepository = mock(ScheduledRetryJobRepository.class);
        paymentIntentRepository = mock(PaymentIntentRepository.class);
        paymentRequestRepository = mock(PaymentRequestRepository.class);
        refundRepository = mock(RefundRepository.class);
        dispatcher = mock(PaymentProviderDispatcher.class);
        providerAccountService = mock(ProviderAccountService.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        metrics = mock(PaymentMetrics.class);

        service = new ScheduledRetryWorkerService(
                retryJobRepository,
                paymentIntentRepository,
                paymentRequestRepository,
                refundRepository,
                dispatcher,
                providerAccountService,
                mock(com.masonx.paygateway.service.billing.InvoicePaymentService.class),
                mock(com.masonx.paygateway.domain.billing.InvoiceRepository.class),
                mock(com.masonx.paygateway.domain.billing.SubscriptionRepository.class),
                outboxEventRepository,
                new ObjectMapper().findAndRegisterModules(),
                metrics,
                new NoopTransactionManager(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "test-worker");
        ReflectionTestUtils.setField(service, "backoffSeconds", 900L);
    }

    @Test
    void captureRetrySuccessMarksIntentAndJobSucceeded() {
        UUID merchantId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ScheduledRetryJob job = captureJob(jobId, merchantId, intentId, accountId, 0, 3);
        PaymentIntent intent = requiresCaptureIntent(intentId, merchantId, accountId);

        when(retryJobRepository.findScheduledForUpdate(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.save(any(ScheduledRetryJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentIntentRepository.findByIdAndMerchantId(intentId, merchantId)).thenReturn(Optional.of(intent));
        when(paymentIntentRepository.findByIdAndMerchantIdForUpdate(intentId, merchantId)).thenReturn(Optional.of(intent));
        when(paymentIntentRepository.save(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRequestRepository.findByPaymentIntentId(intentId)).thenReturn(List.of());
        when(providerAccountService.loadCredentials(accountId)).thenReturn(new StripeCredentials("sk_test", "pk_test"));
        when(dispatcher.captureAtProvider(PaymentProvider.STRIPE, "pi_provider", new StripeCredentials("sk_test", "pk_test")))
                .thenReturn(true);

        service.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(ScheduledRetryStatus.SUCCEEDED);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getCompletedAt()).isEqualTo(NOW);
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    void captureRetryFailureReschedulesWhenAttemptsRemain() {
        UUID merchantId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        ScheduledRetryJob job = captureJob(jobId, merchantId, intentId, accountId, 0, 3);
        PaymentIntent intent = requiresCaptureIntent(intentId, merchantId, accountId);

        when(retryJobRepository.findScheduledForUpdate(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(retryJobRepository.save(any(ScheduledRetryJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentIntentRepository.findByIdAndMerchantId(intentId, merchantId)).thenReturn(Optional.of(intent));
        when(providerAccountService.loadCredentials(accountId)).thenReturn(new StripeCredentials("sk_test", "pk_test"));
        when(dispatcher.captureAtProvider(PaymentProvider.STRIPE, "pi_provider", new StripeCredentials("sk_test", "pk_test")))
                .thenReturn(false);

        service.processJob(jobId);

        assertThat(job.getStatus()).isEqualTo(ScheduledRetryStatus.SCHEDULED);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getNextRunAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(job.getLastErrorCode()).isEqualTo("capture_failed");
        assertThat(job.getLockedAt()).isNull();
        assertThat(job.getLockedBy()).isNull();
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.REQUIRES_CAPTURE);
    }

    private ScheduledRetryJob captureJob(UUID jobId, UUID merchantId, UUID intentId, UUID accountId,
                                         int attemptCount, int maxAttempts) {
        ScheduledRetryJob job = new ScheduledRetryJob();
        ReflectionTestUtils.setField(job, "id", jobId);
        job.setMerchantId(merchantId);
        job.setOperation(ScheduledRetryOperation.PAYMENT_CAPTURE);
        job.setStatus(ScheduledRetryStatus.SCHEDULED);
        job.setPaymentIntentId(intentId);
        job.setConnectorAccountId(accountId);
        job.setAttemptCount(attemptCount);
        job.setMaxAttempts(maxAttempts);
        job.setNextRunAt(NOW);
        return job;
    }

    private PaymentIntent requiresCaptureIntent(UUID intentId, UUID merchantId, UUID accountId) {
        PaymentIntent intent = new PaymentIntent();
        intent.assignId(intentId);
        intent.setMerchantId(merchantId);
        intent.setMode(ApiKeyMode.TEST);
        intent.setStatus(PaymentIntentStatus.REQUIRES_CAPTURE);
        intent.setResolvedProvider(PaymentProvider.STRIPE);
        intent.setConnectorAccountId(accountId);
        intent.setProviderPaymentId("pi_provider");
        intent.setAmount(1000L);
        intent.setCurrency("USD");
        return intent;
    }

    private static final class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
