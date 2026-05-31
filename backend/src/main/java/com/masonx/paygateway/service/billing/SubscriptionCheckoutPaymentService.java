package com.masonx.paygateway.service.billing;

import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodRepository;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodStatus;
import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkRepository;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkStatus;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.PaymentRequest;
import com.masonx.paygateway.domain.payment.PaymentRequestRepository;
import com.masonx.paygateway.domain.payment.PaymentRequestStatus;
import com.masonx.paygateway.domain.payment.PaymentToken;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.ReusablePaymentMethodDispatcher;
import com.masonx.paygateway.provider.ReusablePaymentMethodSetupRequest;
import com.masonx.paygateway.provider.ReusablePaymentMethodSetupResult;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.service.PaymentTokenService;
import com.masonx.paygateway.web.dto.PublicSubscriptionCheckoutResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class SubscriptionCheckoutPaymentService {

    private final SubscriptionCheckoutLinkRepository checkoutLinkRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final PaymentTokenService paymentTokenService;
    private final BillingCustomerRepository customerRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final CredentialsCodec credentialsCodec;
    private final PaymentProviderDispatcher dispatcher;
    private final ReusablePaymentMethodDispatcher reusablePaymentMethodDispatcher;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentInstrumentRepository paymentInstrumentRepository;
    private final CustomerPaymentMethodRepository customerPaymentMethodRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentMetrics metrics;

    public SubscriptionCheckoutPaymentService(SubscriptionCheckoutLinkRepository checkoutLinkRepository,
                                              SubscriptionRepository subscriptionRepository,
                                              SubscriptionItemRepository itemRepository,
                                              PaymentTokenService paymentTokenService,
                                              BillingCustomerRepository customerRepository,
                                              ProviderAccountRepository providerAccountRepository,
                                              CredentialsCodec credentialsCodec,
                                              PaymentProviderDispatcher dispatcher,
                                              ReusablePaymentMethodDispatcher reusablePaymentMethodDispatcher,
                                              PaymentIntentRepository paymentIntentRepository,
                                              PaymentRequestRepository paymentRequestRepository,
                                              PaymentInstrumentRepository paymentInstrumentRepository,
                                              CustomerPaymentMethodRepository customerPaymentMethodRepository,
                                              OutboxEventRepository outboxEventRepository,
                                              PaymentMetrics metrics) {
        this.checkoutLinkRepository = checkoutLinkRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.itemRepository = itemRepository;
        this.paymentTokenService = paymentTokenService;
        this.customerRepository = customerRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.credentialsCodec = credentialsCodec;
        this.dispatcher = dispatcher;
        this.reusablePaymentMethodDispatcher = reusablePaymentMethodDispatcher;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.paymentInstrumentRepository = paymentInstrumentRepository;
        this.customerPaymentMethodRepository = customerPaymentMethodRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.metrics = metrics;
    }

    public PublicSubscriptionCheckoutResponse checkout(String token, String gatewayToken) {
        SubscriptionCheckoutLink link = loadUsableLink(token);
        if (checkoutLinkRepository.claimLink(token) == 0) {
            throw new IllegalStateException("This subscription checkout link is no longer active");
        }

        PaymentToken paymentToken = paymentTokenService.consume(gatewayToken);
        Subscription subscription = loadSubscription(link);
        if (!paymentToken.getMerchantId().equals(subscription.getMerchantId())) {
            checkoutLinkRepository.releaseLink(token);
            throw new IllegalStateException("Payment token does not belong to this merchant");
        }

        ProviderAccount account = providerAccountRepository.findById(paymentToken.getAccountId())
                .orElseThrow(() -> new IllegalStateException("Connector account not found"));
        if (account.getMode() != subscription.getMode()) {
            checkoutLinkRepository.releaseLink(token);
            throw new IllegalStateException("Payment token does not belong to this subscription mode");
        }
        ProviderCredentials credentials = credentialsCodec.decode(account);
        PaymentProvider provider = PaymentProvider.valueOf(paymentToken.getProvider());
        BillingCustomer customer = customerRepository
                .findByIdAndMerchantIdAndMode(subscription.getCustomerId(), subscription.getMerchantId(), subscription.getMode())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        ReusablePaymentMethodSetupResult setupResult = reusablePaymentMethodDispatcher.setup(provider,
                new ReusablePaymentMethodSetupRequest(
                        subscription.getMerchantId(),
                        subscription.getCustomerId(),
                        subscription.getId(),
                        account.getId(),
                        "card",
                        paymentToken.getProviderPmId(),
                        existingProviderCustomerReference(subscription, provider, account),
                        "sub-pm-setup-" + paymentToken.getId(),
                        billingDetails(customer),
                        null),
                credentials);
        if (!setupResult.success()) {
            checkoutLinkRepository.releaseLink(token);
            return new PublicSubscriptionCheckoutResponse(
                    false,
                    "FAILED",
                    subscription.getId(),
                    null,
                    setupResult.failureCode() != null ? setupResult.failureCode() : setupResult.actionType(),
                    setupResult.failureMessage() != null
                            ? setupResult.failureMessage()
                            : "Provider requires a hosted reusable payment method setup flow before activation");
        }

        PaymentInstrument reusableInstrument = storeReusablePaymentInstrument(subscription, paymentToken, setupResult, account);

        // Only INCOMPLETE subscriptions charge on checkout — this is the first-activation path.
        // TRIALING stores the method for future billing. ACTIVE and PAST_DUE update the default
        // payment method without re-charging (payment method update flow).
        if (subscription.getStatus() != SubscriptionStatus.INCOMPLETE) {
            makeDefaultPaymentMethod(subscription, reusableInstrument);
            markLinkUsed(link);
            return new PublicSubscriptionCheckoutResponse(
                    true, subscription.getStatus().name(), subscription.getId(), null, null, null);
        }

        long amount = subscriptionAmount(subscription);
        String idempotencyKey = "sub-" + subscription.getId() + "-" + UUID.randomUUID();

        PaymentIntent intent = new PaymentIntent();
        intent.setMerchantId(subscription.getMerchantId());
        intent.setMode(subscription.getMode());
        intent.setAmount(amount);
        intent.setCurrency(subscription.getCurrency());
        intent.setIdempotencyKey(idempotencyKey);
        intent.setResolvedProvider(provider);
        intent.setConnectorAccountId(account.getId());
        intent.setPaymentMethodType("card");
        intent.setStatus(PaymentIntentStatus.PROCESSING);
        intent.setMetadata("{\"subscriptionId\":\"" + subscription.getId() + "\"}");
        PaymentIntent savedIntent = paymentIntentRepository.save(intent);

        long chargeStart = System.currentTimeMillis();
        ChargeResult result = dispatcher.charge(provider, new ChargeRequest(
                savedIntent.getId(),
                amount,
                subscription.getCurrency(),
                "card",
                reusableInstrument.getTokenReference(),
                reusableInstrument.getProviderCustomerReference(),
                idempotencyKey,
                billingDetails(customer),
                null,
                null,
                null
        ), credentials);
        metrics.recordChargeLatency(provider.name(), System.currentTimeMillis() - chargeStart);

        savedIntent.setStatus(result.success() ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
        savedIntent.setProviderPaymentId(result.providerPaymentId());
        savedIntent.setProviderResponse(result.providerResponseJson());
        paymentIntentRepository.save(savedIntent);

        PaymentRequest attempt = new PaymentRequest();
        attempt.setPaymentIntentId(savedIntent.getId());
        attempt.setAmount(amount);
        attempt.setCurrency(subscription.getCurrency());
        attempt.setPaymentMethodType("card");
        attempt.setStatus(result.success() ? PaymentRequestStatus.SUCCEEDED : PaymentRequestStatus.FAILED);
        attempt.setProviderRequestId(result.providerPaymentId());
        attempt.setProviderResponse(result.providerResponseJson());
        attempt.setFailureCode(result.failureCode());
        attempt.setFailureMessage(result.failureMessage());
        attempt.setConnectorAccountId(account.getId());
        paymentRequestRepository.save(attempt);

        metrics.recordIntentConfirmed(provider.name(), savedIntent.getStatus().name(), result.failureCode());

        if (!result.success()) {
            checkoutLinkRepository.releaseLink(token);
            return new PublicSubscriptionCheckoutResponse(
                    false, "FAILED", subscription.getId(), savedIntent.getId(),
                    result.failureCode(), result.failureMessage());
        }

        makeDefaultPaymentMethod(subscription, reusableInstrument);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscriptionRepository.save(subscription);
        markLinkUsed(link);
        outboxEventRepository.save(new OutboxEvent(
                subscription.getMerchantId(),
                "subscription.activated",
                subscription.getId(),
                "{\"subscriptionId\":\"" + subscription.getId()
                        + "\",\"paymentIntentId\":\"" + savedIntent.getId() + "\"}"));

        return new PublicSubscriptionCheckoutResponse(
                true, "ACTIVE", subscription.getId(), savedIntent.getId(), null, null);
    }

    private SubscriptionCheckoutLink loadUsableLink(String token) {
        SubscriptionCheckoutLink link = checkoutLinkRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Subscription checkout link not found"));
        if (link.getStatus() != SubscriptionCheckoutLinkStatus.ACTIVE
                || (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(Instant.now()))) {
            throw new IllegalStateException("This subscription checkout link is no longer active");
        }
        return link;
    }

    private Subscription loadSubscription(SubscriptionCheckoutLink link) {
        return subscriptionRepository.findByIdAndMerchantId(link.getSubscriptionId(), link.getMerchantId())
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));
    }

    private PaymentInstrument storeReusablePaymentInstrument(Subscription subscription,
                                                             PaymentToken token,
                                                             ReusablePaymentMethodSetupResult setupResult,
                                                             ProviderAccount account) {
        if (token.getInstrumentId() == null) {
            throw new IllegalArgumentException("Payment token is missing an instrument reference");
        }
        PaymentInstrument instrument = paymentInstrumentRepository
                .findByIdAndMerchantId(token.getInstrumentId(), subscription.getMerchantId())
                .orElseThrow(() -> new IllegalArgumentException("Payment instrument not found"));
        instrument.setCustomerId(subscription.getCustomerId());
        instrument.setSource(InstrumentSource.VAULT_TOKEN);
        instrument.setPortability(InstrumentPortability.PROVIDER_SCOPED);
        instrument.setProvider(account.getProvider());
        instrument.setProviderAccountId(account.getId());
        instrument.setProviderCustomerReference(setupResult.providerCustomerReference());
        instrument.setTokenReference(setupResult.reusablePaymentMethodReference());
        return paymentInstrumentRepository.save(instrument);
    }

    private void makeDefaultPaymentMethod(Subscription subscription, PaymentInstrument instrument) {
        customerPaymentMethodRepository.clearDefault(subscription.getMerchantId(), subscription.getCustomerId());
        CustomerPaymentMethod method = customerPaymentMethodRepository
                .findByMerchantIdAndCustomerIdAndPaymentInstrumentId(
                        subscription.getMerchantId(), subscription.getCustomerId(), instrument.getId())
                .orElseGet(CustomerPaymentMethod::new);
        if (method.getId() == null) {
            method.setMerchantId(subscription.getMerchantId());
            method.setCustomerId(subscription.getCustomerId());
            method.setPaymentInstrumentId(instrument.getId());
        }
        method.setStatus(CustomerPaymentMethodStatus.ACTIVE);
        method.setDefaultMethod(true);
        customerPaymentMethodRepository.save(method);
    }

    private String existingProviderCustomerReference(Subscription subscription,
                                                     PaymentProvider provider,
                                                     ProviderAccount account) {
        return paymentInstrumentRepository
                .findFirstByMerchantIdAndCustomerIdAndProviderAndProviderAccountIdAndProviderCustomerReferenceIsNotNullOrderByCreatedAtDesc(
                        subscription.getMerchantId(), subscription.getCustomerId(), provider, account.getId())
                .map(PaymentInstrument::getProviderCustomerReference)
                .orElse(null);
    }

    private BillingDetails billingDetails(BillingCustomer customer) {
        String firstName = null;
        String lastName = null;
        if (customer.getName() != null && !customer.getName().isBlank()) {
            String[] parts = customer.getName().trim().split("\\s+", 2);
            firstName = parts[0];
            if (parts.length > 1) {
                lastName = parts[1];
            }
        }
        return new BillingDetails(firstName, lastName, customer.getEmail(), null, null);
    }

    private long subscriptionAmount(Subscription subscription) {
        long amount = itemRepository
                .findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(subscription.getMerchantId(), subscription.getId())
                .stream()
                .mapToLong(item -> item.getAmount() * item.getQuantity())
                .sum();
        if (amount <= 0) {
            throw new IllegalStateException("Subscription has no billable items");
        }
        return amount;
    }

    private void markLinkUsed(SubscriptionCheckoutLink link) {
        link.setStatus(SubscriptionCheckoutLinkStatus.USED);
        link.setCompletedAt(Instant.now());
        checkoutLinkRepository.save(link);
    }
}
