package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.common.id.SnowflakeIdGenerator;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentLink;
import com.masonx.paygateway.domain.payment.PaymentLinkRepository;
import com.masonx.paygateway.domain.payment.PaymentLinkStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.payment.PaymentRequestStatus;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentToken;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.SimulatorCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.masonx.paygateway.service.GatewayIdService;
import com.masonx.paygateway.service.PaymentTokenService;
import com.masonx.paygateway.service.RoutingEngine;
import com.masonx.paygateway.service.routing.RoutingContext;
import com.masonx.paygateway.web.dto.PublicCheckoutRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicPaymentControllerTest {

    private PaymentLinkRepository paymentLinkRepository;
    private ProviderAccountRepository providerAccountRepository;
    private CredentialsCodec credentialsCodec;
    private PaymentProviderDispatcher dispatcher;
    private PaymentIntentRepository paymentIntentRepository;
    private PaymentRequestRepository paymentRequestRepository;
    private PaymentTokenService paymentTokenService;
    private RoutingEngine routingEngine;
    private OutboxEventRepository outboxEventRepository;
    private PaymentMetrics metrics;
    private PublicPaymentController controller;

    @BeforeEach
    void setUp() {
        paymentLinkRepository = mock(PaymentLinkRepository.class);
        providerAccountRepository = mock(ProviderAccountRepository.class);
        credentialsCodec = mock(CredentialsCodec.class);
        dispatcher = mock(PaymentProviderDispatcher.class);
        paymentIntentRepository = mock(PaymentIntentRepository.class);
        paymentRequestRepository = mock(PaymentRequestRepository.class);
        paymentTokenService = mock(PaymentTokenService.class);
        routingEngine = mock(RoutingEngine.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        metrics = mock(PaymentMetrics.class);

        controller = new PublicPaymentController(
                paymentLinkRepository,
                providerAccountRepository,
                credentialsCodec,
                dispatcher,
                paymentIntentRepository,
                paymentRequestRepository,
                paymentTokenService,
                routingEngine,
                outboxEventRepository,
                new ObjectMapper(),
                metrics,
                new GatewayIdService(new SnowflakeIdGenerator(0)));
    }

    @Test
    void checkout_usesDeterministicProviderIdempotencyKeyDerivedFromLinkAndIntent() {
        UUID merchantId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String token = "plink_token";
        String gatewayToken = "gw_tok_link";

        PaymentLink link = paymentLink(merchantId, linkId, token);
        PaymentToken paymentToken = paymentToken(merchantId, accountId);
        ProviderAccount account = providerAccount(merchantId, accountId);

        when(paymentLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(paymentLinkRepository.claimLink(token)).thenReturn(1);
        when(paymentTokenService.consume(gatewayToken)).thenReturn(paymentToken);
        when(providerAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(routingEngine.supportsCapabilities(eq(account), any(RoutingContext.class))).thenReturn(true);
        when(credentialsCodec.decode(account)).thenReturn(new SimulatorCredentials(true, 1.0));
        when(paymentIntentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.charge(eq(PaymentProvider.SIMULATOR), any(ChargeRequest.class), any()))
                .thenReturn(new ChargeResult(true, "sim_pay_ok", "{}", null, null,
                        false, false, false, null, null, null));

        var response = controller.checkout(token, new PublicCheckoutRequest(gatewayToken));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();

        ArgumentCaptor<ChargeRequest> chargeCaptor = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(dispatcher).charge(eq(PaymentProvider.SIMULATOR), chargeCaptor.capture(), any());
        assertThat(chargeCaptor.getValue().idempotencyKey())
                .isEqualTo("pl-" + linkId + "-pi-" + chargeCaptor.getValue().paymentIntentId());

        ArgumentCaptor<PaymentRequest> attemptCaptor = ArgumentCaptor.forClass(PaymentRequest.class);
        verify(paymentRequestRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(PaymentRequestStatus.SUCCEEDED);
        assertThat(attemptCaptor.getValue().getProviderIdempotencyKey())
                .isEqualTo(chargeCaptor.getValue().idempotencyKey());
    }

    /**
     * The Stripe SDK's create()/retrieve() calls are static and this module's test
     * infrastructure doesn't enable static mocking, so this only covers the branch that
     * returns before touching Stripe: an in-flight attempt found via the idempotent DB
     * state check that hasn't reached the provider yet (a concurrent request is still
     * creating the Stripe PaymentIntent for this exact attempt).
     */
    @Test
    void prepareStripe_inFlightAttemptWithoutProviderContactYet_doesNotCreateDuplicateLocalIntent() {
        UUID merchantId = UUID.randomUUID();
        UUID linkId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String token = "plink_token";
        String expectedKeyPrefix = "pl-" + linkId + "-stripe-pi-";

        PaymentLink link = paymentLink(merchantId, linkId, token);
        link.setPinnedConnectorId(accountId);
        ProviderAccount account = stripeProviderAccount(merchantId, accountId);

        PaymentIntent inFlight = new PaymentIntent();
        ReflectionTestUtils.setField(inFlight, "id", UUID.randomUUID());
        inFlight.setMerchantId(merchantId);
        inFlight.setStatus(PaymentIntentStatus.PROCESSING);

        when(paymentLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(providerAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(routingEngine.supportsCapabilities(eq(account), any(RoutingContext.class))).thenReturn(true);
        when(credentialsCodec.decode(account)).thenReturn(new StripeCredentials("sk_test_123", "pk_test_123"));
        when(paymentIntentRepository.findByMerchantIdAndIdempotencyKeyStartingWithOrderByCreatedAtDesc(
                eq(merchantId), eq(expectedKeyPrefix), any(PageRequest.class)))
                .thenReturn(List.of(inFlight));

        assertThatThrownBy(() -> controller.prepareStripe(token))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retry shortly");

        verify(paymentIntentRepository, never()).save(any(PaymentIntent.class));
    }

    private ProviderAccount stripeProviderAccount(UUID merchantId, UUID accountId) {
        ProviderAccount account = new ProviderAccount();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setMerchantId(merchantId);
        account.setProvider(PaymentProvider.STRIPE);
        account.setMode(ApiKeyMode.TEST);
        account.setLabel("Stripe");
        return account;
    }

    private PaymentLink paymentLink(UUID merchantId, UUID linkId, String token) {
        PaymentLink link = new PaymentLink();
        ReflectionTestUtils.setField(link, "id", linkId);
        link.setMerchantId(merchantId);
        link.setToken(token);
        link.setTitle("Checkout");
        link.setAmount(1_000);
        link.setCurrency("usd");
        link.setMode(ApiKeyMode.TEST);
        link.setStatus(PaymentLinkStatus.ACTIVE);
        link.setExpiresAt(Instant.now().plusSeconds(900));
        return link;
    }

    private PaymentToken paymentToken(UUID merchantId, UUID accountId) {
        PaymentToken token = new PaymentToken();
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        token.setMerchantId(merchantId);
        token.setProvider(PaymentProvider.SIMULATOR.name());
        token.setAccountId(accountId);
        token.setProviderPmId("sim_pm_card");
        token.setExpiresAt(Instant.now().plusSeconds(900));
        return token;
    }

    private ProviderAccount providerAccount(UUID merchantId, UUID accountId) {
        ProviderAccount account = new ProviderAccount();
        ReflectionTestUtils.setField(account, "id", accountId);
        account.setMerchantId(merchantId);
        account.setProvider(PaymentProvider.SIMULATOR);
        account.setMode(ApiKeyMode.TEST);
        account.setLabel("Simulator");
        return account;
    }
}
