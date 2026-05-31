package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkRepository;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkStatus;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.merchant.Merchant;
import com.masonx.paygateway.domain.merchant.MerchantRepository;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.BraintreePaymentProviderService;
import com.masonx.paygateway.provider.credentials.BraintreeCredentials;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.service.billing.SubscriptionCheckoutPaymentService;
import com.masonx.paygateway.web.dto.CheckoutConnectorInfo;
import com.masonx.paygateway.web.dto.PublicSubscriptionCheckoutInfo;
import com.masonx.paygateway.web.dto.PublicSubscriptionCheckoutRequest;
import com.masonx.paygateway.web.dto.PublicSubscriptionCheckoutResponse;
import com.masonx.paygateway.web.dto.SubscriptionItemResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/pub/subscription-checkout")
public class PublicSubscriptionCheckoutController {

    private static final Logger log = LoggerFactory.getLogger(PublicSubscriptionCheckoutController.class);

    private final SubscriptionCheckoutLinkRepository checkoutLinkRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final BillingCustomerRepository customerRepository;
    private final MerchantRepository merchantRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final CredentialsCodec credentialsCodec;
    private final BraintreePaymentProviderService braintreeService;
    private final SubscriptionCheckoutPaymentService paymentService;

    public PublicSubscriptionCheckoutController(SubscriptionCheckoutLinkRepository checkoutLinkRepository,
                                                SubscriptionRepository subscriptionRepository,
                                                SubscriptionItemRepository itemRepository,
                                                BillingCustomerRepository customerRepository,
                                                MerchantRepository merchantRepository,
                                                ProviderAccountRepository providerAccountRepository,
                                                CredentialsCodec credentialsCodec,
                                                BraintreePaymentProviderService braintreeService,
                                                SubscriptionCheckoutPaymentService paymentService) {
        this.checkoutLinkRepository = checkoutLinkRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.itemRepository = itemRepository;
        this.customerRepository = customerRepository;
        this.merchantRepository = merchantRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.credentialsCodec = credentialsCodec;
        this.braintreeService = braintreeService;
        this.paymentService = paymentService;
    }

    @GetMapping("/{token}")
    public ResponseEntity<PublicSubscriptionCheckoutInfo> get(@PathVariable String token) {
        SubscriptionCheckoutLink link = checkoutLinkRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Subscription checkout link not found"));
        Subscription subscription = subscriptionRepository
                .findByIdAndMerchantId(link.getSubscriptionId(), link.getMerchantId())
                .orElseThrow(() -> new IllegalArgumentException("Subscription not found"));
        BillingCustomer customer = customerRepository
                .findByIdAndMerchantIdAndMode(link.getCustomerId(), link.getMerchantId(), subscription.getMode())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));
        Merchant merchant = merchantRepository.findById(link.getMerchantId()).orElse(null);

        boolean active = link.getStatus() == SubscriptionCheckoutLinkStatus.ACTIVE
                && (link.getExpiresAt() == null || link.getExpiresAt().isAfter(Instant.now()));

        var items = itemRepository
                .findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(link.getMerchantId(), subscription.getId())
                .stream()
                .map(SubscriptionItemResponse::from)
                .toList();

        List<CheckoutConnectorInfo> connectors = buildConnectorList(link.getMerchantId(), subscription.getMode());

        return ResponseEntity.ok(new PublicSubscriptionCheckoutInfo(
                link.getToken(),
                subscription.getId(),
                merchant == null ? "" : merchant.getName(),
                customer.getName(),
                customer.getEmail(),
                subscription.getMode().name(),
                subscription.getStatus().name(),
                subscription.getCurrency().toUpperCase(),
                subscription.getIntervalUnit().name(),
                subscription.getIntervalCount(),
                subscription.getTrialEndsAt(),
                active,
                items,
                connectors
        ));
    }

    @PostMapping("/{token}/checkout")
    public ResponseEntity<PublicSubscriptionCheckoutResponse> checkout(
            @PathVariable String token,
            @Valid @RequestBody PublicSubscriptionCheckoutRequest request) {
        return ResponseEntity.ok(paymentService.checkout(token, request.gatewayToken()));
    }

    /**
     * Generates a short-lived Braintree client token for the subscription checkout page.
     * Verifies that the accountId belongs to the subscription's merchant before generating.
     */
    @GetMapping("/braintree-client-token")
    public ResponseEntity<Map<String, String>> braintreeClientToken(
            @RequestParam UUID accountId,
            @RequestParam String subscriptionToken) {
        SubscriptionCheckoutLink link = checkoutLinkRepository.findByToken(subscriptionToken)
                .orElseThrow(() -> new IllegalArgumentException("Subscription checkout link not found"));
        if (link.getStatus() != SubscriptionCheckoutLinkStatus.ACTIVE
                || (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(Instant.now()))) {
            throw new IllegalStateException("Subscription checkout link is no longer active");
        }
        ProviderAccount account = providerAccountRepository.findByIdAndMerchantId(accountId, link.getMerchantId())
                .orElseThrow(() -> new IllegalArgumentException("Connector account not found"));
        if (account.getProvider() != PaymentProvider.BRAINTREE) {
            throw new IllegalArgumentException("Account is not a Braintree connector");
        }
        ProviderCredentials creds = credentialsCodec.decode(account);
        if (!(creds instanceof BraintreeCredentials bt)) {
            throw new IllegalStateException("Failed to decode Braintree credentials");
        }
        String clientToken = braintreeService.generateClientToken(bt);
        return ResponseEntity.ok(Map.of("clientToken", clientToken));
    }

    private List<CheckoutConnectorInfo> buildConnectorList(UUID merchantId,
                                                            com.masonx.paygateway.domain.apikey.ApiKeyMode mode) {
        List<ProviderAccount> accounts = providerAccountRepository
                .findAllByMerchantIdAndModeAndStatusOrderByDisplayOrderAsc(
                        merchantId, mode, ProviderAccountStatus.ACTIVE);

        List<CheckoutConnectorInfo> result = new ArrayList<>();
        for (ProviderAccount account : accounts) {
            try {
                ProviderCredentials creds = credentialsCodec.decode(account);
                String clientKey = creds.clientKey();
                if (clientKey == null || clientKey.isBlank()) continue;
                result.add(new CheckoutConnectorInfo(
                        account.getProvider().name(),
                        account.getId(),
                        clientKey,
                        creds.clientConfig()));
            } catch (Exception e) {
                log.warn("Skipping connector {} — could not decode credentials: {}",
                        account.getId(), e.getMessage());
            }
        }
        return result;
    }
}
