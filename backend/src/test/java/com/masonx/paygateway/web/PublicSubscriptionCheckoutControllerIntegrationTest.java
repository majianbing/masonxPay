package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingCustomer;
import com.masonx.paygateway.domain.billing.BillingCustomerRepository;
import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import com.masonx.paygateway.domain.billing.Subscription;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLink;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkRepository;
import com.masonx.paygateway.domain.billing.SubscriptionCheckoutLinkStatus;
import com.masonx.paygateway.domain.billing.SubscriptionItem;
import com.masonx.paygateway.domain.billing.SubscriptionItemRepository;
import com.masonx.paygateway.domain.billing.SubscriptionRepository;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.merchant.Merchant;
import com.masonx.paygateway.domain.merchant.MerchantRepository;
import com.masonx.paygateway.provider.BraintreePaymentProviderService;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicSubscriptionCheckoutControllerIntegrationTest {

    static {
        System.setProperty("csp.sentinel.log.dir",
                System.getProperty("java.io.tmpdir") + "/masonxpay-sentinel");
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubscriptionCheckoutLinkRepository checkoutLinkRepository;

    @MockBean
    private SubscriptionRepository subscriptionRepository;

    @MockBean
    private SubscriptionItemRepository itemRepository;

    @MockBean
    private BillingCustomerRepository customerRepository;

    @MockBean
    private MerchantRepository merchantRepository;

    @MockBean
    private ProviderAccountRepository providerAccountRepository;

    @MockBean
    private CredentialsCodec credentialsCodec;

    @MockBean
    private BraintreePaymentProviderService braintreePaymentProviderService;

    @Test
    void publicSubscriptionCheckoutLookupReturnsSubscriptionTerms() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        String token = "sub_test";

        SubscriptionCheckoutLink link = checkoutLink(merchantId, customerId, subscriptionId, token);
        Subscription subscription = subscription(merchantId, customerId, subscriptionId);
        BillingCustomer customer = customer(merchantId, customerId);
        Merchant merchant = merchant(merchantId);
        SubscriptionItem item = item(merchantId, subscriptionId);

        when(checkoutLinkRepository.findByToken(token)).thenReturn(Optional.of(link));
        when(subscriptionRepository.findByIdAndMerchantId(subscriptionId, merchantId)).thenReturn(Optional.of(subscription));
        when(customerRepository.findByIdAndMerchantIdAndMode(customerId, merchantId, ApiKeyMode.TEST)).thenReturn(Optional.of(customer));
        when(merchantRepository.findById(merchantId)).thenReturn(Optional.of(merchant));
        when(itemRepository.findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(merchantId, subscriptionId))
                .thenReturn(List.of(item));
        when(providerAccountRepository.findAllByMerchantIdAndModeAndStatusOrderByDisplayOrderAsc(
                any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/pub/subscription-checkout/{token}", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.merchantName").value("Acme"))
                .andExpect(jsonPath("$.customerEmail").value("customer@example.com"))
                .andExpect(jsonPath("$.mode").value("TEST"))
                .andExpect(jsonPath("$.status").value("TRIALING"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.intervalUnit").value("MONTH"))
                .andExpect(jsonPath("$.intervalCount").value(1))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].description").value("Pro plan"))
                .andExpect(jsonPath("$.items[0].amount").value(2900));
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

    private BillingCustomer customer(UUID merchantId, UUID customerId) {
        BillingCustomer customer = new BillingCustomer();
        ReflectionTestUtils.setField(customer, "id", customerId);
        customer.setMerchantId(merchantId);
        customer.setMode(ApiKeyMode.TEST);
        customer.setName("Jane Customer");
        customer.setEmail("customer@example.com");
        return customer;
    }

    private Merchant merchant(UUID merchantId) {
        Merchant merchant = new Merchant();
        ReflectionTestUtils.setField(merchant, "id", merchantId);
        merchant.setOrganizationId(UUID.randomUUID());
        merchant.setName("Acme");
        return merchant;
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
