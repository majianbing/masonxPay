package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodRepository;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkRepository;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkStatus;
import com.masonx.paygateway.domain.billing.SubscriptionItem;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.instrument.InstrumentType;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.payment.PaymentToken;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.ReusablePaymentMethodDispatcher;
import com.masonx.paygateway.provider.ReusablePaymentMethodSetupRequest;
import com.masonx.paygateway.provider.ReusablePaymentMethodSetupResult;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.SimulatorCredentials;
import com.masonx.paygateway.service.PaymentTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionCheckoutPaymentServiceTest {

    private SubscriptionCheckoutLinkRepository checkoutLinkRepository;
    private SubscriptionRepository subscriptionRepository;
    private SubscriptionItemRepository itemRepository;
    private PaymentTokenService paymentTokenService;
    private BillingCustomerRepository customerRepository;
    private ProviderAccountRepository providerAccountRepository;
    private CredentialsCodec credentialsCodec;
    private PaymentInstrumentRepository paymentInstrumentRepository;
    private CustomerPaymentMethodRepository customerPaymentMethodRepository;
    private PaymentProviderDispatcher dispatcher;
    private ReusablePaymentMethodDispatcher reusablePaymentMethodDispatcher;
    private PaymentIntentRepository paymentIntentRepository;
    private PaymentRequestRepository paymentRequestRepository;
    private SubscriptionCheckoutPaymentService service;

    @BeforeEach
    void setUp() {
        checkoutLinkRepository = mock(SubscriptionCheckoutLinkRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        itemRepository = mock(SubscriptionItemRepository.class);
        paymentTokenService = mock(PaymentTokenService.class);
        customerRepository = mock(BillingCustomerRepository.class);
        providerAccountRepository = mock(ProviderAccountRepository.class);
        credentialsCodec = mock(CredentialsCodec.class);
        dispatcher = mock(PaymentProviderDispatcher.class);
        reusablePaymentMethodDispatcher = mock(ReusablePaymentMethodDispatcher.class);
        paymentIntentRepository = mock(PaymentIntentRepository.class);
        paymentRequestRepository = mock(PaymentRequestRepository.class);
        paymentInstrumentRepository = mock(PaymentInstrumentRepository.class);
        customerPaymentMethodRepository = mock(CustomerPaymentMethodRepository.class);
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        PaymentMetrics metrics = mock(PaymentMetrics.class);

        service = new SubscriptionCheckoutPaymentService(
                checkoutLinkRepository,
                subscriptionRepository,
                itemRepository,
                paymentTokenService,
                customerRepository,
                providerAccountRepository,
                credentialsCodec,
                dispatcher,
                reusablePaymentMethodDispatcher,
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
        ProviderAccount account = providerAccount(merchantId, paymentToken.getAccountId());
        BillingCustomer customer = customer(merchantId, customerId);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(checkoutLinkRepository.claimLink(token)).thenReturn(1);
        when(paymentTokenService.consume(gatewayToken)).thenReturn(paymentToken);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(subscription));
        when(providerAccountRepository.findById(paymentToken.getAccountId())).thenReturn(Optional.of(account));
        when(credentialsCodec.decode(account)).thenReturn(new SimulatorCredentials(true, 1.0));
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST))
                .thenReturn(Optional.of(customer));
        when(paymentInstrumentRepository.findByIdAndMerchantId(instrumentId, merchantId)).thenReturn(Optional.of(instrument));
        when(paymentInstrumentRepository.save(instrument)).thenReturn(instrument);
        when(reusablePaymentMethodDispatcher.setup(any(), any(), any()))
                .thenReturn(ReusablePaymentMethodSetupResult.succeeded(
                        "sim_cus_" + customerId,
                        "sim_pm_reusable_" + customerId,
                        "{}"));
        when(customerPaymentMethodRepository.findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
                merchantId, customerId, instrumentId)).thenReturn(Optional.empty());

        var response = service.checkout(token, gatewayToken);

        assertThat(response.success()).isTrue();
        assertThat(response.status()).isEqualTo(SubscriptionStatus.TRIALING.name());
        assertThat(response.paymentIntentId()).isNull();
        assertThat(instrument.getCustomerId()).isEqualTo(customerId);
        assertThat(instrument.getSource()).isEqualTo(InstrumentSource.VAULT_TOKEN);
        assertThat(instrument.getTokenReference()).startsWith("sim_pm_reusable_");
        assertThat(instrument.getProviderCustomerReference()).startsWith("sim_cus_");
        assertThat(link.getStatus()).isEqualTo(SubscriptionCheckoutLinkStatus.USED);
        assertThat(link.getCompletedAt()).isNotNull();
        verify(customerPaymentMethodRepository).clearDefault(merchantId, customerId);
        verify(customerPaymentMethodRepository).save(any(CustomerPaymentMethod.class));
        verify(dispatcher, never()).charge(any(), any(), any());
    }

    @Test
    void failedFirstPaymentDoesNotMakeReusableMethodDefault() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        String token = "sub_paid";
        String gatewayToken = "gw_tok_paid";

        SubscriptionCheckoutLink link = checkoutLink(merchantId, customerId, subscriptionId, token);
        Subscription subscription = subscription(merchantId, customerId, subscriptionId);
        subscription.setStatus(SubscriptionStatus.INCOMPLETE);
        subscription.setTrialEndsAt(null);
        PaymentToken paymentToken = paymentToken(merchantId, instrumentId);
        PaymentInstrument instrument = instrument(merchantId, instrumentId);
        ProviderAccount account = providerAccount(merchantId, paymentToken.getAccountId());
        BillingCustomer customer = customer(merchantId, customerId);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(checkoutLinkRepository.claimLink(token)).thenReturn(1);
        when(paymentTokenService.consume(gatewayToken)).thenReturn(paymentToken);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(subscription));
        when(providerAccountRepository.findById(paymentToken.getAccountId())).thenReturn(Optional.of(account));
        when(credentialsCodec.decode(account)).thenReturn(new SimulatorCredentials(true, 1.0));
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST))
                .thenReturn(Optional.of(customer));
        when(paymentInstrumentRepository.findByIdAndMerchantId(instrumentId, merchantId)).thenReturn(Optional.of(instrument));
        when(paymentInstrumentRepository.save(instrument)).thenReturn(instrument);
        when(reusablePaymentMethodDispatcher.setup(any(), any(), any()))
                .thenReturn(ReusablePaymentMethodSetupResult.succeeded(
                        "sim_cus_" + customerId,
                        "sim_pm_reusable_" + customerId,
                        "{}"));
        when(itemRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(merchantId, subscriptionId))
                .thenReturn(List.of(subscriptionItem(merchantId, subscriptionId)));
        when(paymentIntentRepository.save(any(PaymentIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(dispatcher.charge(any(), any(), any()))
                .thenReturn(new ChargeResult(false, "sim_pay_failed", "{}", "card_declined",
                        "Card declined", false, false, null, null, null));

        var response = service.checkout(token, gatewayToken);

        assertThat(response.success()).isFalse();
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(instrument.getSource()).isEqualTo(InstrumentSource.VAULT_TOKEN);
        assertThat(link.getStatus()).isEqualTo(SubscriptionCheckoutLinkStatus.ACTIVE);
        verify(checkoutLinkRepository).releaseLink(token);
        verify(customerPaymentMethodRepository, never()).clearDefault(any(), any());
        verify(customerPaymentMethodRepository, never()).save(any(CustomerPaymentMethod.class));
    }

    @Test
    void checkout_usedLink_throwsBeforeAnyProviderCall() {
        String token = "used_link";
        SubscriptionCheckoutLink link = checkoutLink(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), token);
        link.setStatus(SubscriptionCheckoutLinkStatus.USED);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));

        assertThatThrownBy(() -> service.checkout(token, "gw_tok_any"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer active");
        verify(dispatcher, never()).charge(any(), any(), any());
    }

    @Test
    void checkout_concurrentClaim_throwsWhenClaimFails() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        String token = "race_link";

        SubscriptionCheckoutLink link = checkoutLink(merchantId, customerId, subscriptionId, token);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(checkoutLinkRepository.claimLink(token)).thenReturn(0); // concurrent claim won

        assertThatThrownBy(() -> service.checkout(token, "gw_tok_any"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer active");
        verify(dispatcher, never()).charge(any(), any(), any());
    }

    @Test
    void nonTrialCheckout_succeeds_activatesSubscriptionAndSetsDefaultMethod() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        String token = "sub_nontrial";
        String gatewayToken = "gw_tok_nontrial";

        SubscriptionCheckoutLink link = checkoutLink(merchantId, customerId, subscriptionId, token);
        Subscription subscription = subscription(merchantId, customerId, subscriptionId);
        subscription.setStatus(SubscriptionStatus.INCOMPLETE);
        subscription.setTrialEndsAt(null);
        PaymentToken paymentToken = paymentToken(merchantId, instrumentId);
        PaymentInstrument instrument = instrument(merchantId, instrumentId);
        ProviderAccount account = providerAccount(merchantId, paymentToken.getAccountId());
        BillingCustomer customer = customer(merchantId, customerId);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(checkoutLinkRepository.claimLink(token)).thenReturn(1);
        when(paymentTokenService.consume(gatewayToken)).thenReturn(paymentToken);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(subscription));
        when(providerAccountRepository.findById(paymentToken.getAccountId())).thenReturn(Optional.of(account));
        when(credentialsCodec.decode(account)).thenReturn(new SimulatorCredentials(true, 1.0));
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST))
                .thenReturn(Optional.of(customer));
        when(reusablePaymentMethodDispatcher.setup(any(), any(), any()))
                .thenReturn(ReusablePaymentMethodSetupResult.succeeded(
                        "sim_cus_" + customerId, "sim_pm_reusable_" + customerId, "{}"));
        when(paymentInstrumentRepository.findByIdAndMerchantId(instrumentId, merchantId)).thenReturn(Optional.of(instrument));
        when(paymentInstrumentRepository.save(instrument)).thenReturn(instrument);
        when(itemRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(merchantId, subscriptionId))
                .thenReturn(List.of(subscriptionItem(merchantId, subscriptionId)));
        when(paymentIntentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(dispatcher.charge(any(), any(), any()))
                .thenReturn(new ChargeResult(true, "sim_pay_ok", "{}", null, null, false, false, null, null, null));
        when(customerPaymentMethodRepository.findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
                merchantId, customerId, instrumentId)).thenReturn(Optional.empty());

        var response = service.checkout(token, gatewayToken);

        assertThat(response.success()).isTrue();
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(link.getStatus()).isEqualTo(SubscriptionCheckoutLinkStatus.USED);
        verify(dispatcher).charge(any(), any(), any());
        verify(customerPaymentMethodRepository).clearDefault(merchantId, customerId);
        verify(customerPaymentMethodRepository).save(any(CustomerPaymentMethod.class));
    }

    @Test
    void reusableMethodSetupFailure_releasesLinkAndDoesNotCharge() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        String token = "sub_setup_fail";
        String gatewayToken = "gw_tok_setup_fail";

        SubscriptionCheckoutLink link = checkoutLink(merchantId, customerId, subscriptionId, token);
        Subscription subscription = subscription(merchantId, customerId, subscriptionId);
        subscription.setStatus(SubscriptionStatus.INCOMPLETE);
        subscription.setTrialEndsAt(null);
        PaymentToken paymentToken = paymentToken(merchantId, instrumentId);
        ProviderAccount account = providerAccount(merchantId, paymentToken.getAccountId());
        BillingCustomer customer = customer(merchantId, customerId);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(checkoutLinkRepository.claimLink(token)).thenReturn(1);
        when(paymentTokenService.consume(gatewayToken)).thenReturn(paymentToken);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(subscription));
        when(providerAccountRepository.findById(paymentToken.getAccountId())).thenReturn(Optional.of(account));
        when(credentialsCodec.decode(account)).thenReturn(new SimulatorCredentials(true, 1.0));
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST))
                .thenReturn(Optional.of(customer));
        when(reusablePaymentMethodDispatcher.setup(any(), any(), any()))
                .thenReturn(ReusablePaymentMethodSetupResult.failed("connector_error", "Setup failed", false));

        var response = service.checkout(token, gatewayToken);

        assertThat(response.success()).isFalse();
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.failureCode()).isEqualTo("connector_error");
        verify(checkoutLinkRepository).releaseLink(token);
        verify(paymentInstrumentRepository, never()).save(any());
        verify(dispatcher, never()).charge(any(), any(), any());
    }

    @Test
    void activeSubscription_checkout_updatesDefaultMethodWithoutCharging() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        String token = "sub_active_update";
        String gatewayToken = "gw_tok_active";

        SubscriptionCheckoutLink link = checkoutLink(merchantId, customerId, subscriptionId, token);
        Subscription subscription = subscription(merchantId, customerId, subscriptionId);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setTrialEndsAt(null);
        PaymentToken paymentToken = paymentToken(merchantId, instrumentId);
        PaymentInstrument instrument = instrument(merchantId, instrumentId);
        ProviderAccount account = providerAccount(merchantId, paymentToken.getAccountId());
        BillingCustomer customer = customer(merchantId, customerId);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(checkoutLinkRepository.claimLink(token)).thenReturn(1);
        when(paymentTokenService.consume(gatewayToken)).thenReturn(paymentToken);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(subscription));
        when(providerAccountRepository.findById(paymentToken.getAccountId())).thenReturn(Optional.of(account));
        when(credentialsCodec.decode(account)).thenReturn(new SimulatorCredentials(true, 1.0));
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST))
                .thenReturn(Optional.of(customer));
        when(paymentInstrumentRepository.findByIdAndMerchantId(instrumentId, merchantId)).thenReturn(Optional.of(instrument));
        when(paymentInstrumentRepository.save(instrument)).thenReturn(instrument);
        when(reusablePaymentMethodDispatcher.setup(any(), any(), any()))
                .thenReturn(ReusablePaymentMethodSetupResult.succeeded("sim_cus_x", "sim_pm_x", "{}"));
        when(customerPaymentMethodRepository.findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
                merchantId, customerId, instrumentId)).thenReturn(Optional.empty());

        var response = service.checkout(token, gatewayToken);

        assertThat(response.success()).isTrue();
        assertThat(response.status()).isEqualTo(SubscriptionStatus.ACTIVE.name());
        assertThat(response.paymentIntentId()).isNull();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE); // unchanged
        verify(dispatcher, never()).charge(any(), any(), any()); // no duplicate charge
        verify(customerPaymentMethodRepository).clearDefault(merchantId, customerId);
        verify(customerPaymentMethodRepository).save(any(CustomerPaymentMethod.class));
    }

    @Test
    void reusableSetup_passesExistingProviderCustomerReferenceToDispatcher() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        UUID instrumentId = UUID.randomUUID();
        String token = "sub_reuse";
        String gatewayToken = "gw_tok_reuse";
        String existingCusRef = "existing_cus_123";

        SubscriptionCheckoutLink link = checkoutLink(merchantId, customerId, subscriptionId, token);
        Subscription subscription = subscription(merchantId, customerId, subscriptionId); // trialing
        PaymentToken paymentToken = paymentToken(merchantId, instrumentId);
        PaymentInstrument instrument = instrument(merchantId, instrumentId);
        ProviderAccount account = providerAccount(merchantId, paymentToken.getAccountId());
        BillingCustomer customer = customer(merchantId, customerId);

        PaymentInstrument priorInstrument = instrument(merchantId, UUID.randomUUID());
        priorInstrument.setProviderCustomerReference(existingCusRef);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(checkoutLinkRepository.claimLink(token)).thenReturn(1);
        when(paymentTokenService.consume(gatewayToken)).thenReturn(paymentToken);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(subscription));
        when(providerAccountRepository.findById(paymentToken.getAccountId())).thenReturn(Optional.of(account));
        when(credentialsCodec.decode(account)).thenReturn(new SimulatorCredentials(true, 1.0));
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST))
                .thenReturn(Optional.of(customer));
        when(paymentInstrumentRepository
                .findFirstByMerchantIdAndCustomerIdAndProviderAndProviderAccountIdAndProviderCustomerReferenceIsNotNullOrderByCreatedAtDesc(
                        merchantId, customerId, PaymentProvider.SIMULATOR, account.getId()))
                .thenReturn(Optional.of(priorInstrument));
        when(reusablePaymentMethodDispatcher.setup(any(), any(), any()))
                .thenReturn(ReusablePaymentMethodSetupResult.succeeded("sim_cus_new", "sim_pm_new", "{}"));
        when(paymentInstrumentRepository.findByIdAndMerchantId(instrumentId, merchantId)).thenReturn(Optional.of(instrument));
        when(paymentInstrumentRepository.save(instrument)).thenReturn(instrument);
        when(customerPaymentMethodRepository.findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
                merchantId, customerId, instrumentId)).thenReturn(Optional.empty());

        ArgumentCaptor<ReusablePaymentMethodSetupRequest> captor =
                ArgumentCaptor.forClass(ReusablePaymentMethodSetupRequest.class);

        service.checkout(token, gatewayToken);

        verify(reusablePaymentMethodDispatcher).setup(any(), captor.capture(), any());
        assertThat(captor.getValue().existingProviderCustomerReference()).isEqualTo(existingCusRef);
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
        ReflectionTestUtils.setField(token, "id", UUID.randomUUID());
        token.setMerchantId(merchantId);
        token.setProvider(PaymentProvider.SIMULATOR.name());
        token.setAccountId(UUID.randomUUID());
        token.setProviderPmId("sim_pm");
        token.setInstrumentId(instrumentId);
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

    private BillingCustomer customer(UUID merchantId, UUID customerId) {
        BillingCustomer customer = new BillingCustomer();
        ReflectionTestUtils.setField(customer, "id", customerId);
        customer.setMerchantId(merchantId);
        customer.setMode(ApiKeyMode.TEST);
        customer.setEmail("buyer@example.com");
        customer.setName("Test Buyer");
        return customer;
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

    private SubscriptionItem subscriptionItem(UUID merchantId, UUID subscriptionId) {
        SubscriptionItem item = new SubscriptionItem();
        item.setMerchantId(merchantId);
        item.setSubscriptionId(subscriptionId);
        item.setDescription("Monthly plan");
        item.setAmount(1_000);
        item.setQuantity(1);
        return item;
    }
}
