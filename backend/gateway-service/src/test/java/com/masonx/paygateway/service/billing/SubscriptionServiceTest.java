package com.masonx.paygateway.service.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkRepository;
import com.masonx.paygateway.domain.billing.SubscriptionItem;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.web.dto.CreateSubscriptionCheckoutLinkRequest;
import com.masonx.paygateway.web.dto.CreateSubscriptionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionServiceTest {

    private BillingCustomerRepository customerRepository;
    private SubscriptionRepository subscriptionRepository;
    private SubscriptionItemRepository itemRepository;
    private SubscriptionCheckoutLinkRepository checkoutLinkRepository;
    private com.masonx.paygateway.service.GatewayIdService gatewayIdService;
    private SubscriptionService service;

    @BeforeEach
    void setUp() {
        customerRepository = mock(BillingCustomerRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        itemRepository = mock(SubscriptionItemRepository.class);
        checkoutLinkRepository = mock(SubscriptionCheckoutLinkRepository.class);
        gatewayIdService = mock(com.masonx.paygateway.service.GatewayIdService.class);
        service = new SubscriptionService(
                customerRepository,
                subscriptionRepository,
                itemRepository,
                checkoutLinkRepository,
                new ObjectMapper().findAndRegisterModules(),
                gatewayIdService);
        ReflectionTestUtils.setField(service, "payBaseUrl", "http://localhost:3000");
    }

    @Test
    void createTrialSubscriptionStoresMerchantScopedItems() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST))
                .thenReturn(Optional.of(customer(merchantId, customerId)));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(invocation -> {
            Subscription subscription = invocation.getArgument(0);
            ReflectionTestUtils.setField(subscription, "id", subscriptionId);
            return subscription;
        });
        when(itemRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(merchantId, subscriptionId))
                .thenReturn(List.of(item(merchantId, subscriptionId)));

        var response = service.create(merchantId, ApiKeyMode.TEST, new CreateSubscriptionRequest(
                customerId,
                "USD",
                BillingIntervalUnit.MONTH,
                1,
                7,
                Map.of("campaign", "trial"),
                List.of(new CreateSubscriptionRequest.SubscriptionItemRequest("Pro plan", 2900, 1))));

        assertThat(response.status()).isEqualTo(SubscriptionStatus.TRIALING.name());
        assertThat(response.mode()).isEqualTo(ApiKeyMode.TEST.name());
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.trialEndsAt()).isNotNull();
        assertThat(response.metadata()).containsEntry("campaign", "trial");
        assertThat(response.items()).hasSize(1);
        verify(itemRepository).saveAll(any());
    }

    @Test
    void createRejectsUnknownCustomer() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(merchantId, ApiKeyMode.TEST, new CreateSubscriptionRequest(
                customerId,
                "USD",
                BillingIntervalUnit.MONTH,
                1,
                0,
                Map.of(),
                List.of(new CreateSubscriptionRequest.SubscriptionItemRequest("Pro plan", 2900, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer not found");
    }

    @Test
    void createCheckoutLinkReturnsShareableSubscriptionUrl() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = subscription(merchantId, customerId, subscriptionId);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(subscription));
        when(checkoutLinkRepository.save(any(SubscriptionCheckoutLink.class))).thenAnswer(invocation -> {
            SubscriptionCheckoutLink link = invocation.getArgument(0);
            ReflectionTestUtils.setField(link, "id", UUID.randomUUID());
            return link;
        });

        var response = service.createCheckoutLink(
                merchantId,
                subscriptionId,
                new CreateSubscriptionCheckoutLinkRequest(Instant.now().plusSeconds(3600)));

        assertThat(response.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.token()).startsWith("sub_");
        assertThat(response.checkoutUrl()).startsWith("http://localhost:3000/subscribe/sub_");
    }

    @Test
    void createCheckoutLink_canceledSubscription_throwsIllegalState() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = subscription(merchantId, UUID.randomUUID(), subscriptionId);
        subscription.setStatus(SubscriptionStatus.CANCELED);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.createCheckoutLink(merchantId, subscriptionId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be created");
    }

    @Test
    void createCheckoutLink_unpaidSubscription_throwsIllegalState() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = subscription(merchantId, UUID.randomUUID(), subscriptionId);
        subscription.setStatus(SubscriptionStatus.UNPAID);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.createCheckoutLink(merchantId, subscriptionId, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void createCheckoutLink_expiresInPast_throwsIllegalArgument() {
        UUID merchantId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = subscription(merchantId, UUID.randomUUID(), subscriptionId);
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId))
                .thenReturn(Optional.of(subscription));

        var request = new CreateSubscriptionCheckoutLinkRequest(Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> service.createCheckoutLink(merchantId, subscriptionId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiration must be in the future");
    }

    @Test
    void createNonTrialSubscription_startsAsIncomplete() {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST))
                .thenReturn(Optional.of(customer(merchantId, customerId)));
        when(subscriptionRepository.save(any(Subscription.class))).thenAnswer(inv -> {
            Subscription s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", subscriptionId);
            return s;
        });
        when(itemRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(merchantId, subscriptionId))
                .thenReturn(List.of(item(merchantId, subscriptionId)));

        var response = service.create(merchantId, ApiKeyMode.TEST, new CreateSubscriptionRequest(
                customerId, "USD", BillingIntervalUnit.MONTH, 1, 0, null,
                List.of(new CreateSubscriptionRequest.SubscriptionItemRequest("Pro plan", 2900, 1))));

        assertThat(response.status()).isEqualTo(SubscriptionStatus.INCOMPLETE.name());
        assertThat(response.trialEndsAt()).isNull();
    }

    private BillingCustomer customer(UUID merchantId, UUID customerId) {
        BillingCustomer customer = new BillingCustomer();
        ReflectionTestUtils.setField(customer, "id", customerId);
        customer.setMerchantId(merchantId);
        customer.setMode(ApiKeyMode.TEST);
        return customer;
    }

    private Subscription subscription(UUID merchantId, UUID customerId, UUID subscriptionId) {
        Subscription subscription = new Subscription();
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        subscription.setMerchantId(merchantId);
        subscription.setCustomerId(customerId);
        subscription.setMode(ApiKeyMode.TEST);
        subscription.setCurrency("usd");
        subscription.setIntervalUnit(BillingIntervalUnit.MONTH);
        subscription.setIntervalCount(1);
        subscription.setStatus(SubscriptionStatus.INCOMPLETE);
        return subscription;
    }

    private SubscriptionItem item(UUID merchantId, UUID subscriptionId) {
        SubscriptionItem item = new SubscriptionItem();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        item.setMerchantId(merchantId);
        item.setSubscriptionId(subscriptionId);
        item.setDescription("Pro plan");
        item.setAmount(2900);
        item.setQuantity(1);
        return item;
    }
}
