package com.masonx.paygateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.apikey.ApiKeyType;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.redis.PaymentIdempotencyCache;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import com.masonx.paygateway.service.retry.ScheduledRetryService;
import com.masonx.paygateway.sharding.PaymentShardRegistryRepository;
import com.masonx.paygateway.sharding.PaymentShardRouter;
import com.masonx.paygateway.web.dto.ConfirmPaymentIntentRequest;
import com.masonx.paygateway.web.dto.CreatePaymentIntentRequest;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentIntentServiceTest {

    @Mock PaymentIntentRepository paymentIntentRepository;
    @Mock PaymentRequestRepository paymentRequestRepository;
    @Mock RoutingEngine routingEngine;
    @Mock PaymentProviderDispatcher dispatcher;
    @Mock ProviderAccountService providerAccountService;
    @Mock ProviderAccountRepository providerAccountRepository;
    @Mock PaymentInstrumentRepository paymentInstrumentRepository;
    @Mock PaymentTokenService paymentTokenService;
    @Mock PaymentRetryOrchestratorService retryOrchestrator;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock PaymentShardRegistryRepository shardRegistryRepository;
    @Mock PaymentShardRouter shardRouter;
    @Mock PaymentIdempotencyCache idempotencyCache;
    @Mock PlatformTransactionManager txManager;
    @Mock PaymentMetrics metrics;
    @Mock ScheduledRetryService scheduledRetryService;

    private PaymentIntentService service;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final UUID merchantId = UUID.randomUUID();
    private final UUID keyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentIntentService(
                paymentIntentRepository, paymentRequestRepository,
                routingEngine, dispatcher, providerAccountService,
                providerAccountRepository, paymentInstrumentRepository, paymentTokenService, retryOrchestrator,
                objectMapper, outboxEventRepository, shardRegistryRepository, shardRouter,
                idempotencyCache, txManager, metrics, scheduledRetryService);
        lenient().when(idempotencyCache.find(any(), anyString())).thenReturn(Optional.empty());
    }

    private ApiKeyAuthentication auth(ApiKeyType type) {
        return new ApiKeyAuthentication(keyId, merchantId, ApiKeyMode.TEST, type);
    }

    private PaymentIntent savedIntent(UUID id) {
        PaymentIntent pi = new PaymentIntent();
        ReflectionTestUtils.setField(pi, "id", id);
        pi.setMerchantId(merchantId);
        pi.setAmount(1000L);
        pi.setCurrency("USD");
        pi.setIdempotencyKey("idem-key");
        pi.setMode(ApiKeyMode.TEST);
        pi.setStatus(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
        pi.setCaptureMethod(CaptureMethod.AUTOMATIC);
        return pi;
    }

    private ProviderAccount account(PaymentProvider provider) {
        ProviderAccount account = new ProviderAccount();
        ReflectionTestUtils.setField(account, "id", UUID.randomUUID());
        account.setMerchantId(merchantId);
        account.setProvider(provider);
        return account;
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_idempotencyKey_returnsExistingIntent() {
        UUID existingId = UUID.randomUUID();
        PaymentIntent existing = savedIntent(existingId);

        when(shardRegistryRepository.findIdempotencyRoute(merchantId, "idem-key"))
                .thenReturn(Optional.of(new com.masonx.paygateway.sharding.PaymentIdempotencyRoute(
                        merchantId, "idem-key", existingId, 3,
                        com.masonx.paygateway.sharding.IdempotencyReservationStatus.COMPLETED)));
        when(paymentIntentRepository.findByIdAndMerchantId(existingId, merchantId))
                .thenReturn(Optional.of(existing));
        when(paymentRequestRepository.findByPaymentIntentId(existingId))
                .thenReturn(List.of());

        CreatePaymentIntentRequest req = new CreatePaymentIntentRequest(
                1000L, "USD", "idem-key", null, null, null, null, null, null, null, null, null);

        PaymentIntentResponse resp = service.create(auth(ApiKeyType.SECRET), req);

        assertThat(resp.id()).isEqualTo(existingId);
        verify(paymentIntentRepository, never()).save(any());
    }

    @Test
    void create_publishableKey_throwsAccessDenied() {
        // requireSecretKey() throws before any repo is consulted
        CreatePaymentIntentRequest req = new CreatePaymentIntentRequest(
                1000L, "USD", "idem-key-2", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(auth(ApiKeyType.PUBLISHABLE), req))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void create_newIntent_setsRequiresPaymentMethodStatus() {
        UUID newId = UUID.randomUUID();

        when(shardRegistryRepository.findIdempotencyRoute(merchantId, "new-key"))
                .thenReturn(Optional.empty());
        when(shardRouter.shardForPaymentId(any())).thenReturn(7);
        when(shardRegistryRepository.reserveIdempotencyKey(eq(merchantId), eq("new-key"), any(), eq(7)))
                .thenReturn(true);
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> {
            PaymentIntent pi = inv.getArgument(0);
            ReflectionTestUtils.setField(pi, "id", newId);
            return pi;
        });
        when(paymentRequestRepository.findByPaymentIntentId(newId)).thenReturn(List.of());

        CreatePaymentIntentRequest req = new CreatePaymentIntentRequest(
                2500L, "EUR", "new-key", null, null, null, null, null, null, null, null, null);

        PaymentIntentResponse resp = service.create(auth(ApiKeyType.SECRET), req);

        assertThat(resp.id()).isEqualTo(newId);
        assertThat(resp.status()).isEqualTo(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD.name());
        assertThat(resp.amount()).isEqualTo(2500L);
        assertThat(resp.currency()).isEqualTo("EUR");
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    void get_ownIntent_returnsResponse() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent pi = savedIntent(intentId);

        when(paymentIntentRepository.findByIdAndMerchantId(intentId, merchantId))
                .thenReturn(Optional.of(pi));
        when(paymentRequestRepository.findByPaymentIntentId(intentId)).thenReturn(List.of());

        PaymentIntentResponse resp = service.get(auth(ApiKeyType.SECRET), intentId);
        assertThat(resp.id()).isEqualTo(intentId);
    }

    @Test
    void get_crossMerchant_throwsNotFound() {
        UUID intentId = UUID.randomUUID();
        when(paymentIntentRepository.findByIdAndMerchantId(intentId, merchantId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(auth(ApiKeyType.SECRET), intentId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    // ── confirm ───────────────────────────────────────────────────────────────

    @Test
    void confirm_routedFallbackCandidate_usesSameAccountOnlyRetryContext() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent pi = savedIntent(intentId);
        ProviderAccount primary = account(PaymentProvider.STRIPE);
        ProviderAccount fallback = account(PaymentProvider.SQUARE);

        when(paymentIntentRepository.findByIdAndMerchantIdForUpdate(intentId, merchantId))
                .thenReturn(Optional.of(pi));
        when(routingEngine.resolvePlan(any()))
                .thenReturn(Optional.of(new RoutePlan(List.of(
                        new RouteCandidate(primary), new RouteCandidate(fallback)))));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(retryOrchestrator.execute(eq(pi), eq("pm_123"), eq("card"), any(RoutePlan.class),
                eq(PaymentRetryContext.sameAccountOnly())))
                .thenReturn(new PaymentRetryOrchestratorService.Result(
                        new ChargeResult(true, "pi_provider_123", "{}", null, null,
                                false, false, false, null, null, null),
                        new RouteCandidate(primary), 1));
        when(paymentIntentRepository.findByIdForUpdate(intentId)).thenReturn(Optional.of(pi));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRequestRepository.findByPaymentIntentId(intentId)).thenReturn(List.of());

        service.confirm(auth(ApiKeyType.SECRET), intentId,
                new ConfirmPaymentIntentRequest("pm_123", "card", null, null));

        org.mockito.ArgumentCaptor<RoutePlan> routePlanCaptor =
                org.mockito.ArgumentCaptor.forClass(RoutePlan.class);
        verify(retryOrchestrator).execute(eq(pi), eq("pm_123"), eq("card"), routePlanCaptor.capture(),
                eq(PaymentRetryContext.sameAccountOnly()));
        assertThat(routePlanCaptor.getValue().candidates())
                .extracting(RouteCandidate::accountId)
                .containsExactly(primary.getId(), fallback.getId());
    }

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancel_requiresPaymentMethod_succeeds() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent pi = savedIntent(intentId);
        pi.setStatus(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);

        when(paymentIntentRepository.findByIdAndMerchantIdForUpdate(intentId, merchantId))
                .thenReturn(Optional.of(pi));
        when(paymentIntentRepository.findByIdForUpdate(intentId)).thenReturn(Optional.of(pi));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRequestRepository.findByPaymentIntentId(intentId)).thenReturn(List.of());
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntentResponse resp = service.cancel(auth(ApiKeyType.SECRET), intentId);

        assertThat(resp.status()).isEqualTo(PaymentIntentStatus.CANCELED.name());
    }

    @Test
    void cancel_processingStatus_throwsIllegalState() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent pi = savedIntent(intentId);
        pi.setStatus(PaymentIntentStatus.PROCESSING);

        // TX 1 snapshot read
        when(paymentIntentRepository.findByIdAndMerchantIdForUpdate(intentId, merchantId))
                .thenReturn(Optional.of(pi));

        assertThatThrownBy(() -> service.cancel(auth(ApiKeyType.SECRET), intentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be canceled");
    }

    @Test
    void cancel_succeededStatus_throwsIllegalState() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent pi = savedIntent(intentId);
        pi.setStatus(PaymentIntentStatus.SUCCEEDED);

        when(paymentIntentRepository.findByIdAndMerchantIdForUpdate(intentId, merchantId))
                .thenReturn(Optional.of(pi));

        assertThatThrownBy(() -> service.cancel(auth(ApiKeyType.SECRET), intentId))
                .isInstanceOf(IllegalStateException.class);
    }
}
