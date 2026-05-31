package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkRepository;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkStatus;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.merchant.Merchant;
import com.masonx.paygateway.domain.merchant.MerchantRepository;
import com.masonx.paygateway.service.billing.SubscriptionCheckoutPaymentService;
import com.masonx.paygateway.web.dto.PublicSubscriptionCheckoutInfo;
import com.masonx.paygateway.web.dto.PublicSubscriptionCheckoutRequest;
import com.masonx.paygateway.web.dto.PublicSubscriptionCheckoutResponse;
import com.masonx.paygateway.web.dto.SubscriptionItemResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/pub/subscription-checkout")
public class PublicSubscriptionCheckoutController {

    private final SubscriptionCheckoutLinkRepository checkoutLinkRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionItemRepository itemRepository;
    private final BillingCustomerRepository customerRepository;
    private final MerchantRepository merchantRepository;
    private final SubscriptionCheckoutPaymentService paymentService;

    public PublicSubscriptionCheckoutController(SubscriptionCheckoutLinkRepository checkoutLinkRepository,
                                                SubscriptionRepository subscriptionRepository,
                                                SubscriptionItemRepository itemRepository,
                                                BillingCustomerRepository customerRepository,
                                                MerchantRepository merchantRepository,
                                                SubscriptionCheckoutPaymentService paymentService) {
        this.checkoutLinkRepository = checkoutLinkRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.itemRepository = itemRepository;
        this.customerRepository = customerRepository;
        this.merchantRepository = merchantRepository;
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
                items
        ));
    }

    @PostMapping("/{token}/checkout")
    public ResponseEntity<PublicSubscriptionCheckoutResponse> checkout(
            @PathVariable String token,
            @Valid @RequestBody PublicSubscriptionCheckoutRequest request) {
        return ResponseEntity.ok(paymentService.checkout(token, request.gatewayToken()));
    }
}
