package com.masonx.paygateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.apikey.ApiKeyType;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import com.masonx.paygateway.web.dto.CreatePaymentIntentRequest;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
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
    @Mock PaymentTokenService paymentTokenService;
    @Mock FailoverPolicy failoverPolicy;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock PlatformTransactionManager txManager;

    private PaymentIntentService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID merchantId = UUID.randomUUID();
    private final UUID keyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentIntentService(
                paymentIntentRepository, paymentRequestRepository,
                routingEngine, dispatcher, providerAccountService,
                providerAccountRepository, paymentTokenService, failoverPolicy,
                objectMapper, eventPublisher, txManager);
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

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_idempotencyKey_returnsExistingIntent() {
        UUID existingId = UUID.randomUUID();
        PaymentIntent existing = savedIntent(existingId);

        when(paymentIntentRepository.findByMerchantIdAndIdempotencyKey(merchantId, "idem-key"))
                .thenReturn(Optional.of(existing));
        when(paymentRequestRepository.findByPaymentIntentId(existingId))
                .thenReturn(List.of());

        CreatePaymentIntentRequest req = new CreatePaymentIntentRequest(
                1000L, "USD", "idem-key", null, null, null, null, null);

        PaymentIntentResponse resp = service.create(auth(ApiKeyType.SECRET), req);

        assertThat(resp.id()).isEqualTo(existingId);
        verify(paymentIntentRepository, never()).save(any());
    }

    @Test
    void create_publishableKey_throwsAccessDenied() {
        // requireSecretKey() throws before any repo is consulted
        CreatePaymentIntentRequest req = new CreatePaymentIntentRequest(
                1000L, "USD", "idem-key-2", null, null, null, null, null);

        assertThatThrownBy(() -> service.create(auth(ApiKeyType.PUBLISHABLE), req))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void create_newIntent_setsRequiresPaymentMethodStatus() {
        UUID newId = UUID.randomUUID();

        when(paymentIntentRepository.findByMerchantIdAndIdempotencyKey(merchantId, "new-key"))
                .thenReturn(Optional.empty());
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> {
            PaymentIntent pi = inv.getArgument(0);
            ReflectionTestUtils.setField(pi, "id", newId);
            return pi;
        });
        when(paymentRequestRepository.findByPaymentIntentId(newId)).thenReturn(List.of());

        CreatePaymentIntentRequest req = new CreatePaymentIntentRequest(
                2500L, "EUR", "new-key", null, null, null, null, null);

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

    // ── cancel ────────────────────────────────────────────────────────────────

    @Test
    void cancel_requiresPaymentMethod_succeeds() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent pi = savedIntent(intentId);
        pi.setStatus(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);

        when(paymentIntentRepository.findByIdAndMerchantId(intentId, merchantId))
                .thenReturn(Optional.of(pi));
        when(paymentIntentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRequestRepository.findByPaymentIntentId(intentId)).thenReturn(List.of());

        PaymentIntentResponse resp = service.cancel(auth(ApiKeyType.SECRET), intentId);

        assertThat(resp.status()).isEqualTo(PaymentIntentStatus.CANCELED.name());
    }

    @Test
    void cancel_processingStatus_throwsIllegalState() {
        UUID intentId = UUID.randomUUID();
        PaymentIntent pi = savedIntent(intentId);
        pi.setStatus(PaymentIntentStatus.PROCESSING);

        when(paymentIntentRepository.findByIdAndMerchantId(intentId, merchantId))
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

        when(paymentIntentRepository.findByIdAndMerchantId(intentId, merchantId))
                .thenReturn(Optional.of(pi));

        assertThatThrownBy(() -> service.cancel(auth(ApiKeyType.SECRET), intentId))
                .isInstanceOf(IllegalStateException.class);
    }
}
