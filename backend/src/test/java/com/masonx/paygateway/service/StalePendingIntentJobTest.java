package com.masonx.paygateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StalePendingIntentJobTest {

    @Mock PaymentIntentRepository  paymentIntentRepository;
    @Mock PaymentRequestRepository paymentRequestRepository;
    @Mock PaymentProviderDispatcher dispatcher;
    @Mock ProviderAccountService   providerAccountService;
    @Mock OutboxEventRepository    outboxEventRepository;
    @Mock PlatformTransactionManager txManager;
    @Mock PaymentMetrics           metrics;

    private StalePendingIntentJob job;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final StripeCredentials STUB_CREDS = new StripeCredentials("sk_test_stub", "pk_test_stub");

    private final UUID merchantId = UUID.randomUUID();
    private final UUID accountId  = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Make TransactionTemplate execute lambdas synchronously (no real DB needed)
        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);

        job = new StalePendingIntentJob(
                paymentIntentRepository, paymentRequestRepository,
                dispatcher, providerAccountService,
                outboxEventRepository, objectMapper, txManager, metrics);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PaymentIntent processingIntent() {
        return processingIntent(PaymentProvider.STRIPE, "card");
    }

    private PaymentIntent processingIntent(PaymentProvider provider, String pmType) {
        PaymentIntent pi = new PaymentIntent();
        ReflectionTestUtils.setField(pi, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(pi, "createdAt", Instant.now().minus(Duration.ofHours(2)));
        ReflectionTestUtils.setField(pi, "updatedAt", Instant.now().minus(Duration.ofHours(2)));
        pi.setMerchantId(merchantId);
        pi.setAmount(1000L);
        pi.setCurrency("USD");
        pi.setMode(ApiKeyMode.TEST);
        pi.setIdempotencyKey(UUID.randomUUID().toString());
        pi.setCaptureMethod(CaptureMethod.AUTOMATIC);
        pi.setStatus(PaymentIntentStatus.PROCESSING);
        pi.setResolvedProvider(provider);
        pi.setConnectorAccountId(accountId);
        pi.setProviderPaymentId("pi_test_" + UUID.randomUUID());
        pi.setPaymentMethodType(pmType);
        return pi;
    }

    /** Stubs findStaleProcessing to return the given intents, and sets up save() pass-through. */
    private void givenStaleIntents(PaymentIntent... intents) {
        when(paymentIntentRepository.findStaleProcessing(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(intents));
    }

    /**
     * Stubs findById so that applyLocalStatus re-reads the intent still in PROCESSING
     * (i.e. no race condition — safe to write).
     */
    private void givenFreshIntentStillProcessing(PaymentIntent pi) {
        when(paymentIntentRepository.findById(pi.getId())).thenReturn(Optional.of(pi));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRequestRepository.findByPaymentIntentId(pi.getId())).thenReturn(List.of());
    }

    // ── empty batch ───────────────────────────────────────────────────────────

    @Test
    void reconcile_emptyBatch_doesNothing() {
        when(paymentIntentRepository.findStaleProcessing(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        job.reconcile();

        verify(dispatcher, never()).syncStatus(any(), any(), any());
        verify(paymentIntentRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    // ── no provider contact ───────────────────────────────────────────────────

    @Test
    void reconcile_noProviderPaymentId_cancelsLocallyWithoutProviderCall() {
        PaymentIntent pi = processingIntent();
        pi.setProviderPaymentId(null);
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);

        job.reconcile();

        verify(dispatcher, never()).syncStatus(any(), any(), any());
        verify(dispatcher, never()).cancelAtProvider(any(), any(), any());

        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.CANCELED);
    }

    @Test
    void reconcile_noConnectorAccountId_cancelsLocallyWithoutProviderCall() {
        PaymentIntent pi = processingIntent();
        pi.setConnectorAccountId(null);
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);

        job.reconcile();

        verify(dispatcher, never()).syncStatus(any(), any(), any());
        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.CANCELED);
    }

    // ── credentials failure ───────────────────────────────────────────────────

    @Test
    void reconcile_credentialsLoadFails_skipsIntentWithoutStatusChange() {
        PaymentIntent pi = processingIntent();
        givenStaleIntents(pi);
        when(providerAccountService.loadCredentials(accountId))
                .thenThrow(new IllegalStateException("Connector not found"));

        job.reconcile();

        verify(paymentIntentRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    // ── provider returns definitive status ────────────────────────────────────

    @Test
    void reconcile_providerSaysSucceeded_appliesSucceededAndWritesOutboxEvent() {
        PaymentIntent pi = processingIntent();
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(eq(PaymentProvider.STRIPE), anyString(), any()))
                .thenReturn(Optional.of(PaymentIntentStatus.SUCCEEDED));

        job.reconcile();

        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        verify(outboxEventRepository).save(any());
        // cancelAtProvider must NOT be called when provider already gave a definitive status
        verify(dispatcher, never()).cancelAtProvider(any(), any(), any());
    }

    @Test
    void reconcile_providerSaysFailed_appliesFailedAndWritesOutboxEvent() {
        PaymentIntent pi = processingIntent();
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(eq(PaymentProvider.STRIPE), anyString(), any()))
                .thenReturn(Optional.of(PaymentIntentStatus.FAILED));

        job.reconcile();

        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.FAILED);
        verify(outboxEventRepository).save(any());
    }

    @Test
    void reconcile_providerSaysCanceled_appliesCanceled() {
        PaymentIntent pi = processingIntent();
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(eq(PaymentProvider.STRIPE), anyString(), any()))
                .thenReturn(Optional.of(PaymentIntentStatus.CANCELED));

        job.reconcile();

        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.CANCELED);
    }

    // ── provider still in-flight → cancel ────────────────────────────────────

    @Test
    void reconcile_providerStillInflight_cancelSucceeds_markedCanceled() {
        PaymentIntent pi = processingIntent();
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(dispatcher.cancelAtProvider(any(), any(), any())).thenReturn(true);

        job.reconcile();

        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.CANCELED);
        verify(dispatcher).cancelAtProvider(eq(PaymentProvider.STRIPE), anyString(), any());
    }

    @Test
    void reconcile_providerStillInflight_cancelFails_stillMarkedCanceled() {
        // Even when the provider rejects our cancel (e.g. already settling),
        // we still mark the local record CANCELED after logging the warning.
        PaymentIntent pi = processingIntent();
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(any(), any(), any())).thenReturn(Optional.empty());
        when(dispatcher.cancelAtProvider(any(), any(), any())).thenReturn(false);

        job.reconcile();

        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.CANCELED);
    }

    // ── race condition ────────────────────────────────────────────────────────

    @Test
    void reconcile_raceCondition_alreadyResolvedByWebhook_skipsWrite() {
        // Simulate: stale query returns PROCESSING intent, but by the time
        // applyLocalStatus re-reads it, a concurrent webhook has already set SUCCEEDED.
        PaymentIntent staleSnapshot = processingIntent();
        givenStaleIntents(staleSnapshot);

        PaymentIntent freshFromDb = processingIntent();
        ReflectionTestUtils.setField(freshFromDb, "id", staleSnapshot.getId());
        freshFromDb.setStatus(PaymentIntentStatus.SUCCEEDED); // already resolved
        when(paymentIntentRepository.findById(staleSnapshot.getId()))
                .thenReturn(Optional.of(freshFromDb));
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(any(), any(), any()))
                .thenReturn(Optional.of(PaymentIntentStatus.CANCELED));

        job.reconcile();

        // The re-read shows SUCCEEDED (not PROCESSING) → save must NOT be called
        verify(paymentIntentRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    // ── batch resilience ──────────────────────────────────────────────────────

    @Test
    void reconcile_exceptionInOneIntent_continuesProcessingRemainder() {
        PaymentIntent failing  = processingIntent();
        PaymentIntent succeeds = processingIntent();

        when(paymentIntentRepository.findStaleProcessing(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(failing, succeeds));

        // First intent: credentials load blows up
        when(providerAccountService.loadCredentials(accountId))
                .thenThrow(new RuntimeException("transient DB error"))
                .thenReturn(STUB_CREDS); // second call succeeds

        // Second intent: sync says SUCCEEDED
        when(dispatcher.syncStatus(any(), any(), any()))
                .thenReturn(Optional.of(PaymentIntentStatus.SUCCEEDED));
        givenFreshIntentStillProcessing(succeeds);

        job.reconcile();

        // Only the second intent should be saved
        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(succeeds.getId());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
    }

    // ── threshold values ──────────────────────────────────────────────────────

    @Test
    void reconcile_passesCorrectThresholdsToQuery() {
        when(paymentIntentRepository.findStaleProcessing(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        Instant before = Instant.now();
        job.reconcile();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> cardCaptor  = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> otherCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(paymentIntentRepository).findStaleProcessing(
                cardCaptor.capture(), otherCaptor.capture(), any(Pageable.class));

        // cardThreshold should be approximately now - 30 min (within 5 seconds of test execution)
        Instant expectedCard  = before.minus(Duration.ofMinutes(30));
        Instant expectedOther = before.minus(Duration.ofDays(7));

        assertThat(cardCaptor.getValue())
                .isBetween(expectedCard.minusSeconds(5), after.minus(Duration.ofMinutes(30)));
        assertThat(otherCaptor.getValue())
                .isBetween(expectedOther.minusSeconds(5), after.minus(Duration.ofDays(7)));
    }

    // ── Square and Braintree dispatch ─────────────────────────────────────────

    @Test
    void reconcile_squareIntent_dispatchesToSquareSyncStatus() {
        PaymentIntent pi = processingIntent(PaymentProvider.SQUARE, "card");
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(eq(PaymentProvider.SQUARE), anyString(), any()))
                .thenReturn(Optional.of(PaymentIntentStatus.SUCCEEDED));

        job.reconcile();

        verify(dispatcher).syncStatus(eq(PaymentProvider.SQUARE), anyString(), any());
    }

    @Test
    void reconcile_braintreeIntent_dispatchesToBraintreeSyncStatus() {
        PaymentIntent pi = processingIntent(PaymentProvider.BRAINTREE, "card");
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(eq(PaymentProvider.BRAINTREE), anyString(), any()))
                .thenReturn(Optional.of(PaymentIntentStatus.SUCCEEDED));

        job.reconcile();

        verify(dispatcher).syncStatus(eq(PaymentProvider.BRAINTREE), anyString(), any());
    }

    // ── null paymentMethodType treated conservatively ─────────────────────────

    @Test
    void reconcile_nullPaymentMethodType_queriedWithOtherThreshold() {
        // NULL payment method type falls into the "other" (7-day) bucket at the DB level.
        // We verify the job still processes such intents correctly end-to-end.
        PaymentIntent pi = processingIntent(PaymentProvider.STRIPE, null);
        givenStaleIntents(pi);
        givenFreshIntentStillProcessing(pi);
        when(providerAccountService.loadCredentials(accountId)).thenReturn(STUB_CREDS);
        when(dispatcher.syncStatus(any(), any(), any()))
                .thenReturn(Optional.of(PaymentIntentStatus.CANCELED));

        job.reconcile();

        ArgumentCaptor<PaymentIntent> saved = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentIntentStatus.CANCELED);
    }
}
