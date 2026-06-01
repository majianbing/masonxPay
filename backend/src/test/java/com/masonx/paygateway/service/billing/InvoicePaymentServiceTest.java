package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodRepository;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodStatus;
import com.masonx.paygateway.domain.billing.Invoice;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttempt;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttemptRepository;
import com.masonx.paygateway.domain.billing.InvoicePaymentAttemptStatus;
import com.masonx.paygateway.domain.billing.InvoiceRepository;
import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.instrument.InstrumentType;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.SimulatorCredentials;
import com.masonx.paygateway.web.dto.InvoicePaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

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

class InvoicePaymentServiceTest {

    private InvoiceRepository invoiceRepository;
    private InvoicePaymentAttemptRepository attemptRepository;
    private CustomerPaymentMethodRepository paymentMethodRepository;
    private PaymentInstrumentRepository instrumentRepository;
    private ProviderAccountRepository providerAccountRepository;
    private SubscriptionRepository subscriptionRepository;
    private PaymentIntentRepository paymentIntentRepository;
    private OutboxEventRepository outboxEventRepository;
    private CredentialsCodec credentialsCodec;
    private PaymentProviderDispatcher dispatcher;
    private InvoicePaymentService service;

    private UUID merchantId;
    private UUID customerId;
    private UUID subscriptionId;
    private UUID invoiceId;
    private UUID instrumentId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        attemptRepository = mock(InvoicePaymentAttemptRepository.class);
        paymentMethodRepository = mock(CustomerPaymentMethodRepository.class);
        instrumentRepository = mock(PaymentInstrumentRepository.class);
        providerAccountRepository = mock(ProviderAccountRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        paymentIntentRepository = mock(PaymentIntentRepository.class);
        outboxEventRepository = mock(OutboxEventRepository.class);
        credentialsCodec = mock(CredentialsCodec.class);
        dispatcher = mock(PaymentProviderDispatcher.class);

        service = new InvoicePaymentService(
                invoiceRepository, attemptRepository, paymentMethodRepository,
                instrumentRepository, providerAccountRepository, subscriptionRepository,
                paymentIntentRepository, outboxEventRepository, credentialsCodec,
                dispatcher, mock(com.masonx.paygateway.service.retry.ScheduledRetryService.class),
                noopTxManager());

        merchantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        instrumentId = UUID.randomUUID();
        accountId = UUID.randomUUID();
    }

    @Test
    void pay_success_marksInvoicePaidAndSubscriptionActive() {
        Invoice invoice = invoice(invoiceId, merchantId, customerId, subscriptionId, InvoiceStatus.OPEN, 2900);
        Subscription sub = subscription(subscriptionId, merchantId, SubscriptionStatus.PAST_DUE);
        PaymentInstrument instrument = instrument(instrumentId, merchantId, accountId);
        ProviderAccount account = account(accountId, merchantId, ApiKeyMode.TEST);

        stubHappyPath(invoice, sub, instrument, account);
        when(dispatcher.charge(any(), any(), any()))
                .thenReturn(new ChargeResult(true, "sim_pay_ok", "{}", null, null, false, false, null, null, null));

        InvoicePaymentResponse response = service.pay(merchantId, invoiceId);

        assertThat(response.success()).isTrue();
        assertThat(response.invoiceStatus()).isEqualTo(InvoiceStatus.PAID.name());
        assertThat(response.subscriptionStatus()).isEqualTo("ACTIVE");
        assertThat(response.attemptNumber()).isEqualTo(1);
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getAmountPaid()).isEqualTo(2900);
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        ArgumentCaptor<InvoicePaymentAttempt> attemptCaptor = ArgumentCaptor.forClass(InvoicePaymentAttempt.class);
        verify(attemptRepository).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo(InvoicePaymentAttemptStatus.SUCCEEDED);
        assertThat(attemptCaptor.getValue().getMode()).isEqualTo(ApiKeyMode.TEST);
    }

    @Test
    void pay_chargeFailure_invoiceStaysOpenAndSubscriptionBecomesPastDue() {
        Invoice invoice = invoice(invoiceId, merchantId, customerId, subscriptionId, InvoiceStatus.OPEN, 2900);
        Subscription sub = subscription(subscriptionId, merchantId, SubscriptionStatus.ACTIVE);
        PaymentInstrument instrument = instrument(instrumentId, merchantId, accountId);
        ProviderAccount account = account(accountId, merchantId, ApiKeyMode.TEST);

        stubHappyPath(invoice, sub, instrument, account);
        when(dispatcher.charge(any(), any(), any()))
                .thenReturn(new ChargeResult(false, null, "{}", "card_declined", "Card declined", false, false, null, null, null));

        InvoicePaymentResponse response = service.pay(merchantId, invoiceId);

        assertThat(response.success()).isFalse();
        assertThat(response.invoiceStatus()).isEqualTo(InvoiceStatus.OPEN.name());
        assertThat(response.failureCode()).isEqualTo("card_declined");
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(invoice.getNextPaymentAttemptAt()).isNotNull();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void pay_alreadyPaid_returnsSuccessWithoutCharging() {
        Invoice invoice = invoice(invoiceId, merchantId, customerId, subscriptionId, InvoiceStatus.PAID, 2900);
        when(invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)).thenReturn(Optional.of(invoice));

        InvoicePaymentResponse response = service.pay(merchantId, invoiceId);

        assertThat(response.success()).isTrue();
        assertThat(response.invoiceStatus()).isEqualTo(InvoiceStatus.PAID.name());
        verify(dispatcher, never()).charge(any(), any(), any());
        verify(paymentIntentRepository, never()).save(any());
    }

    @Test
    void pay_noDefaultPaymentMethod_throwsIllegalState() {
        Invoice invoice = invoice(invoiceId, merchantId, customerId, subscriptionId, InvoiceStatus.OPEN, 2900);
        when(invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndDefaultMethodTrueAndStatus(
                merchantId, customerId, CustomerPaymentMethodStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pay(merchantId, invoiceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No active default payment method");
        verify(dispatcher, never()).charge(any(), any(), any());
    }

    @Test
    void pay_instrumentMissingReusableReference_throwsIllegalState() {
        Invoice invoice = invoice(invoiceId, merchantId, customerId, subscriptionId, InvoiceStatus.OPEN, 2900);
        CustomerPaymentMethod method = paymentMethod(merchantId, customerId, instrumentId);
        PaymentInstrument instrument = instrument(instrumentId, merchantId, accountId);
        instrument.setSource(InstrumentSource.PROVIDER_TOKEN); // not vaulted
        instrument.setProviderCustomerReference(null);

        when(invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndDefaultMethodTrueAndStatus(
                merchantId, customerId, CustomerPaymentMethodStatus.ACTIVE)).thenReturn(Optional.of(method));
        when(instrumentRepository.findByIdAndMerchantId(instrumentId, merchantId)).thenReturn(Optional.of(instrument));

        assertThatThrownBy(() -> service.pay(merchantId, invoiceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not set up for off-session charging");
        verify(dispatcher, never()).charge(any(), any(), any());
    }

    @Test
    void pay_requiresAction_treatedAsFailure() {
        Invoice invoice = invoice(invoiceId, merchantId, customerId, subscriptionId, InvoiceStatus.OPEN, 2900);
        Subscription sub = subscription(subscriptionId, merchantId, SubscriptionStatus.ACTIVE);
        PaymentInstrument instrument = instrument(instrumentId, merchantId, accountId);
        ProviderAccount account = account(accountId, merchantId, ApiKeyMode.TEST);

        stubHappyPath(invoice, sub, instrument, account);
        // Provider asks for 3DS — not possible off-session
        when(dispatcher.charge(any(), any(), any()))
                .thenReturn(new ChargeResult(false, null, "{}", null, null, false, true, "redirect_url", "https://bank.example.com/3ds", null));

        InvoicePaymentResponse response = service.pay(merchantId, invoiceId);

        assertThat(response.success()).isFalse();
        assertThat(response.failureCode()).isEqualTo("requires_customer_action");
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void pay_chargeUsesInstrumentProviderAccountId() {
        Invoice invoice = invoice(invoiceId, merchantId, customerId, subscriptionId, InvoiceStatus.OPEN, 2900);
        Subscription sub = subscription(subscriptionId, merchantId, SubscriptionStatus.ACTIVE);
        PaymentInstrument instrument = instrument(instrumentId, merchantId, accountId);
        ProviderAccount account = account(accountId, merchantId, ApiKeyMode.TEST);

        stubHappyPath(invoice, sub, instrument, account);
        when(dispatcher.charge(any(), any(), any()))
                .thenReturn(new ChargeResult(true, "sim_pay_ok", "{}", null, null, false, false, null, null, null));

        service.pay(merchantId, invoiceId);

        // Verify the account used is exactly the instrument's providerAccountId — no routing engine.
        // save() is called twice: once to create the intent (Tx A) and once to update status (Tx B).
        ArgumentCaptor<PaymentIntent> intentCaptor = ArgumentCaptor.forClass(PaymentIntent.class);
        verify(paymentIntentRepository, org.mockito.Mockito.times(2)).save(intentCaptor.capture());
        assertThat(intentCaptor.getAllValues().get(0).getConnectorAccountId()).isEqualTo(accountId);
        verify(providerAccountRepository).findById(accountId);
    }

    @Test
    void pay_attemptNumberIncrementsPerCall() {
        Invoice invoice = invoice(invoiceId, merchantId, customerId, subscriptionId, InvoiceStatus.OPEN, 2900);
        Subscription sub = subscription(subscriptionId, merchantId, SubscriptionStatus.PAST_DUE);
        PaymentInstrument instrument = instrument(instrumentId, merchantId, accountId);
        ProviderAccount account = account(accountId, merchantId, ApiKeyMode.TEST);

        // Two prior attempts already exist
        InvoicePaymentAttempt prior1 = new InvoicePaymentAttempt();
        prior1.setAttemptNumber(1);
        InvoicePaymentAttempt prior2 = new InvoicePaymentAttempt();
        prior2.setAttemptNumber(2);

        stubHappyPath(invoice, sub, instrument, account);
        when(attemptRepository.findByMerchantIdAndInvoiceIdOrderByAttemptNumberAsc(merchantId, invoiceId))
                .thenReturn(List.of(prior1, prior2));
        when(dispatcher.charge(any(), any(), any()))
                .thenReturn(new ChargeResult(true, "sim_pay_ok", "{}", null, null, false, false, null, null, null));

        InvoicePaymentResponse response = service.pay(merchantId, invoiceId);

        assertThat(response.attemptNumber()).isEqualTo(3);
    }

    // --- Helpers ---

    private void stubHappyPath(Invoice invoice, Subscription sub,
                                PaymentInstrument instrument, ProviderAccount account) {
        CustomerPaymentMethod method = paymentMethod(merchantId, customerId, instrumentId);
        when(invoiceRepository.findByIdAndMerchantId(invoiceId, merchantId)).thenReturn(Optional.of(invoice));
        when(paymentMethodRepository.findByMerchantIdAndCustomerIdAndDefaultMethodTrueAndStatus(
                merchantId, customerId, CustomerPaymentMethodStatus.ACTIVE)).thenReturn(Optional.of(method));
        when(instrumentRepository.findByIdAndMerchantId(instrumentId, merchantId)).thenReturn(Optional.of(instrument));
        when(providerAccountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(credentialsCodec.decode(account)).thenReturn(new SimulatorCredentials(true, 1.0));
        when(paymentIntentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> {
            PaymentIntent pi = inv.getArgument(0);
            ReflectionTestUtils.setField(pi, "id", UUID.randomUUID());
            return pi;
        });
        when(attemptRepository.findByMerchantIdAndInvoiceIdOrderByAttemptNumberAsc(merchantId, invoiceId))
                .thenReturn(List.of());
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(sub));
    }

    private Invoice invoice(UUID id, UUID merchantId, UUID customerId, UUID subscriptionId,
                            InvoiceStatus status, long amountDue) {
        Invoice inv = new Invoice();
        ReflectionTestUtils.setField(inv, "id", id);
        inv.setMerchantId(merchantId);
        inv.setCustomerId(customerId);
        inv.setSubscriptionId(subscriptionId);
        inv.setMode(ApiKeyMode.TEST);
        inv.setStatus(status);
        inv.setAmountDue(amountDue);
        inv.setAmountPaid(0);
        inv.setCurrency("usd");
        inv.setPeriodStart(Instant.now().minusSeconds(86400));
        inv.setPeriodEnd(Instant.now().plusSeconds(86400));
        return inv;
    }

    private CustomerPaymentMethod paymentMethod(UUID merchantId, UUID customerId, UUID instrumentId) {
        CustomerPaymentMethod method = new CustomerPaymentMethod();
        ReflectionTestUtils.setField(method, "id", UUID.randomUUID());
        method.setMerchantId(merchantId);
        method.setCustomerId(customerId);
        method.setPaymentInstrumentId(instrumentId);
        method.setStatus(CustomerPaymentMethodStatus.ACTIVE);
        method.setDefaultMethod(true);
        return method;
    }

    private PaymentInstrument instrument(UUID id, UUID merchantId, UUID accountId) {
        PaymentInstrument inst = new PaymentInstrument();
        ReflectionTestUtils.setField(inst, "id", id);
        inst.setMerchantId(merchantId);
        inst.setType(InstrumentType.CARD);
        inst.setSource(InstrumentSource.VAULT_TOKEN);
        inst.setPortability(InstrumentPortability.PROVIDER_SCOPED);
        inst.setProvider(PaymentProvider.SIMULATOR);
        inst.setProviderAccountId(accountId);
        inst.setProviderCustomerReference("sim_cus_abc");
        inst.setTokenReference("sim_pm_reusable_abc");
        return inst;
    }

    private ProviderAccount account(UUID id, UUID merchantId, ApiKeyMode mode) {
        ProviderAccount account = new ProviderAccount();
        ReflectionTestUtils.setField(account, "id", id);
        account.setMerchantId(merchantId);
        account.setProvider(PaymentProvider.SIMULATOR);
        account.setMode(mode);
        account.setLabel("Simulator");
        return account;
    }

    private Subscription subscription(UUID id, UUID merchantId, SubscriptionStatus status) {
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
