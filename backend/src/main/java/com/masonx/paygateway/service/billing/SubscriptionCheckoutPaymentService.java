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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${app.pay-base-url:http://localhost:3000}")
    private String payBaseUrl;

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
                            : "Provider requires a hosted reusable payment method setup flow before activation",
                    null);
        }

        PaymentInstrument reusableInstrument = storeReusablePaymentInstrument(subscription, paymentToken, setupResult, account);

        // Only INCOMPLETE subscriptions charge on checkout — this is the first-activation path.
        // TRIALING stores the method for future billing. ACTIVE and PAST_DUE update the default
        // payment method without re-charging (payment method update flow).
        if (subscription.getStatus() != SubscriptionStatus.INCOMPLETE) {
            makeDefaultPaymentMethod(subscription, reusableInstrument);
            markLinkUsed(link);
            return new PublicSubscriptionCheckoutResponse(
                    true, subscription.getStatus().name(), subscription.getId(), null, null, null, null);
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

        // 3DS return URL: /subscribe/3ds-return?subscriptionToken={token}
        // Stripe appends payment_intent_client_secret and redirect_status automatically.
        String returnUrl = payBaseUrl + "/subscribe/3ds-return?subscriptionToken=" + token;

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
                returnUrl
        ), credentials);
        metrics.recordChargeLatency(provider.name(), System.currentTimeMillis() - chargeStart);

        // 3DS / SCA required — park the intent and let the SDK handle the challenge
        if (result.requiresAction()) {
            savedIntent.setStatus(PaymentIntentStatus.REQUIRES_ACTION);
            savedIntent.setProviderPaymentId(result.providerPaymentId());
            savedIntent.setActionType(result.actionType());
            savedIntent.setActionUrl(result.actionUrl());
            paymentIntentRepository.save(savedIntent);
            // Link stays claimed during 3DS to prevent a parallel checkout attempt.
            // It will be released on cancel (/cancel-3ds) or stay used on success.
            return new PublicSubscriptionCheckoutResponse(
                    false, "REQUIRES_ACTION", subscription.getId(), savedIntent.getId(),
                    null, null,
                    new PublicSubscriptionCheckoutResponse.ProviderAction(
                            result.actionType(), result.actionUrl(), result.clientSecret()));
        }

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
                    result.failureCode(), result.failureMessage(), null);
        }

        activateSubscription(subscription, link, reusableInstrument, savedIntent);

        return new PublicSubscriptionCheckoutResponse(
                true, "ACTIVE", subscription.getId(), savedIntent.getId(), null, null, null);
    }

    /**
     * Resumes a subscription checkout after a 3DS / SCA challenge completes.
     * Idempotent: if the activation has already been recorded for this intent, returns the
     * existing result without re-running side effects.
     */
    public PublicSubscriptionCheckoutResponse resumeAfter3ds(String token, UUID piId) {
        SubscriptionCheckoutLink link = checkoutLinkRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Subscription checkout link not found"));
        Subscription subscription = loadSubscription(link);

        PaymentIntent intent = paymentIntentRepository.findById(piId)
                .filter(pi -> pi.getMerchantId().equals(subscription.getMerchantId()))
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

        boolean success = intent.getStatus() == PaymentIntentStatus.SUCCEEDED;

        if (success && paymentRequestRepository.findByPaymentIntentId(piId).isEmpty()) {
            PaymentRequest attempt = new PaymentRequest();
            attempt.setPaymentIntentId(piId);
            attempt.setAmount(intent.getAmount());
            attempt.setCurrency(intent.getCurrency());
            attempt.setPaymentMethodType("card");
            attempt.setStatus(PaymentRequestStatus.SUCCEEDED);
            attempt.setProviderRequestId(intent.getProviderPaymentId());
            attempt.setConnectorAccountId(intent.getConnectorAccountId());
            paymentRequestRepository.save(attempt);

            PaymentInstrument reusableInstrument = paymentInstrumentRepository
                    .findFirstByMerchantIdAndCustomerIdAndProviderAndProviderAccountIdAndProviderCustomerReferenceIsNotNullOrderByCreatedAtDesc(
                            subscription.getMerchantId(), subscription.getCustomerId(),
                            intent.getResolvedProvider(), intent.getConnectorAccountId())
                    .orElseThrow(() -> new IllegalStateException("Reusable payment instrument not found"));
            activateSubscription(subscription, link, reusableInstrument, intent);

            metrics.recordIntentConfirmed(
                    intent.getResolvedProvider() != null ? intent.getResolvedProvider().name() : "unknown",
                    PaymentIntentStatus.SUCCEEDED.name(),
                    null);
        }

        return new PublicSubscriptionCheckoutResponse(
                success, intent.getStatus().name(), subscription.getId(), intent.getId(), null, null, null);
    }

    /**
     * Cancels a REQUIRES_ACTION payment intent when the customer cancels the 3DS challenge.
     * Releases the checkout link back to ACTIVE so the customer can retry.
     */
    public void cancel3ds(String token, UUID piId) {
        SubscriptionCheckoutLink link = checkoutLinkRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Subscription checkout link not found"));
        Subscription subscription = loadSubscription(link);

        PaymentIntent intent = paymentIntentRepository.findById(piId)
                .filter(pi -> pi.getMerchantId().equals(subscription.getMerchantId()))
                .filter(pi -> pi.getStatus() == PaymentIntentStatus.REQUIRES_ACTION)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found or not awaiting authentication"));

        if (intent.getProviderPaymentId() != null && intent.getConnectorAccountId() != null) {
            try {
                ProviderCredentials creds = credentialsCodec.decode(
                        providerAccountRepository.findById(intent.getConnectorAccountId()).orElseThrow());
                dispatcher.cancelAtProvider(intent.getResolvedProvider(), intent.getProviderPaymentId(), creds);
            } catch (Exception e) {
                // best-effort — the PI may already be expired at the provider's side
            }
        }

        intent.setStatus(PaymentIntentStatus.CANCELED);
        paymentIntentRepository.save(intent);
        checkoutLinkRepository.releaseLink(token);
    }

    private void activateSubscription(Subscription subscription, SubscriptionCheckoutLink link,
                                       PaymentInstrument reusableInstrument, PaymentIntent savedIntent) {
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
