package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodRepository;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkRepository;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkStatus;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.instrument.InstrumentType;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.payment.PaymentToken;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.service.PaymentTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionCheckoutPaymentServiceTest {

    private SubscriptionCheckoutLinkRepository checkoutLinkRepository;
    private SubscriptionRepository subscriptionRepository;
    private PaymentTokenService paymentTokenService;
    private PaymentInstrumentRepository paymentInstrumentRepository;
    private CustomerPaymentMethodRepository customerPaymentMethodRepository;
    private PaymentProviderDispatcher dispatcher;
    private SubscriptionCheckoutPaymentService service;

    @BeforeEach
    void setUp() {
        checkoutLinkRepository = mock(SubscriptionCheckoutLinkRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        SubscriptionItemRepository itemRepository = mock(SubscriptionItemRepository.class);
        paymentTokenService = mock(PaymentTokenService.class);
        ProviderAccountRepository providerAccountRepository = mock(ProviderAccountRepository.class);
        CredentialsCodec credentialsCodec = mock(CredentialsCodec.class);
        dispatcher = mock(PaymentProviderDispatcher.class);
        PaymentIntentRepository paymentIntentRepository = mock(PaymentIntentRepository.class);
        PaymentRequestRepository paymentRequestRepository = mock(PaymentRequestRepository.class);
        paymentInstrumentRepository = mock(PaymentInstrumentRepository.class);
        customerPaymentMethodRepository = mock(CustomerPaymentMethodRepository.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        PaymentMetrics metrics = mock(PaymentMetrics.class);

        service = new SubscriptionCheckoutPaymentService(
                checkoutLinkRepository,
                subscriptionRepository,
                itemRepository,
                paymentTokenService,
                providerAccountRepository,
                credentialsCodec,
                dispatcher,
                paymentIntentRepository,
                paymentRequestRepository,
                paymentInstrumentRepository,
                customerPaymentMethodRepository,
                outboxEventRepository,
                metrics);
    }

    @Test
    void trialCheckoutStoresDefaultMethodWithoutChargingProvider() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        String token = "sub_trial";
        String gatewayToken = "gw_tok_abc";

        SubscriptionCheckoutLink link = checkoutLink(merchantId, customerId, subscriptionId, token);
        Subscription subscription = subscription(merchantId, customerId, subscriptionId);
        PaymentToken paymentToken = paymentToken(merchantId, instrumentId);
        PaymentInstrument instrument = instrument(merchantId, instrumentId);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(checkoutLinkRepository.claimLink(token)).thenReturn(1);
        when(paymentTokenService.consume(gatewayToken)).thenReturn(paymentToken);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(subscription));
        when(paymentInstrumentRepository.findByIdAndMerchantId(instrumentId, merchantId)).thenReturn(Optional.of(instrument));
        when(customerPaymentMethodRepository.findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
                merchantId, customerId, instrumentId)).thenReturn(Optional.empty());

        var response = service.checkout(token, gatewayToken);

        assertThat(response.success()).isTrue();
        assertThat(response.status()).isEqualTo(SubscriptionStatus.TRIALING.name());
        assertThat(response.paymentIntentId()).isNull();
        assertThat(instrument.getCustomerId()).isEqualTo(customerId);
        assertThat(link.getStatus()).isEqualTo(SubscriptionCheckoutLinkStatus.USED);
        assertThat(link.getCompletedAt()).isNotNull();
        verify(customerPaymentMethodRepository).clearDefault(merchantId, customerId);
        verify(customerPaymentMethodRepository).save(any(CustomerPaymentMethod.class));
        verify(dispatcher, never()).charge(any(), any(), any());
    }

    private SubscriptionCheckoutLink checkoutLink(UUID merchantId, UUID customerId, UUID subscriptionId, String token) {
        SubscriptionCheckoutLink link = new SubscriptionCheckoutLink();
        ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
        link.setMerchantId(merchantId);
        link.setCustomerId(customerId);
        link.setSubscriptionId(subscriptionId);
        link.setToken(token);
        link.setStatus(SubscriptionCheckoutLinkStatus.ACTIVE);
        link.setExpiresAt(Instant.now().plusSeconds(3600));
        return link;
    }

    private Subscription subscription(UUID merchantId, UUID customerId, UUID subscriptionId) {
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setMerchantId(merchantId);
        subscription.setCustomerId(customerId);
        subscription.setMode(ApiKeyMode.TEST);
        subscription.setStatus(SubscriptionStatus.TRIALING);
        subscription.setCurrency("usd");
        subscription.setIntervalUnit(BillingIntervalUnit.MONTH);
        subscription.setIntervalCount(1);
        subscription.setTrialEndsAt(Instant.now().plusSeconds(86400));
        return subscription;
    }

    private PaymentToken paymentToken(UUID merchantId, UUID instrumentId) {
        PaymentToken token = new PaymentToken();
        token.setMerchantId(merchantId);
        token.setProvider(PaymentProvider.SIMULATOR.name());
        token.setAccountId(UUID.randomUUID());
        token.setProviderPmId("sim_pm");
        token.setInstrumentId(instrumentId);
        token.setExpiresAt(Instant.now().plusSeconds(900));
        return token;
    }

    private PaymentInstrument instrument(UUID merchantId, UUID instrumentId) {
        PaymentInstrument instrument = new PaymentInstrument();
        ReflectionTestUtils.setField(instrument, "id", instrumentId);
        instrument.setMerchantId(merchantId);
        instrument.setType(InstrumentType.CARD);
        instrument.setSource(InstrumentSource.PROVIDER_TOKEN);
        instrument.setPortability(InstrumentPortability.PROVIDER_SCOPED);
        instrument.setProvider(PaymentProvider.SIMULATOR);
        instrument.setProviderAccountId(UUID.randomUUID());
        instrument.setTokenReference("sim_pm");
        return instrument;
    }
}
