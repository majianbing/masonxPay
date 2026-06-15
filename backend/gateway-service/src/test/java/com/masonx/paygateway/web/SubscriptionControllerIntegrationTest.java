package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.billing.BillingIntervalUnit;
import com.masonx.paygateway.domain.billing.SubscriptionStatus;
import com.masonx.paygateway.security.rbac.GatewayPermissionEvaluator;
import com.masonx.paygateway.service.billing.SubscriptionInvoiceService;
import com.masonx.paygateway.service.billing.SubscriptionService;
import com.masonx.paygateway.web.dto.CreateSubscriptionCheckoutLinkRequest;
import com.masonx.paygateway.web.dto.CreateSubscriptionRequest;
import com.masonx.paygateway.web.dto.SubscriptionCheckoutLinkResponse;
import com.masonx.paygateway.web.dto.SubscriptionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.data.domain.PageImpl;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class SubscriptionControllerIntegrationTest {

    static {
        System.setProperty("csp.sentinel.log.dir",
                System.getProperty("java.io.tmpdir") + "/masonxpay-sentinel");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean SubscriptionService service;
    @MockBean SubscriptionInvoiceService invoiceService;
    @MockBean GatewayPermissionEvaluator permissionEvaluator;

    private UUID merchantId;
    private UUID customerId;
    private UUID subscriptionId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        when(permissionEvaluator.hasPermission(any(), eq((Serializable) merchantId), any(), any()))
                .thenReturn(true);
    }

    @Test
    void list_returns200WithSubscriptions() throws Exception {
        when(service.list(eq(merchantId), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(subscriptionResponse(merchantId, customerId, subscriptionId))));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/subscriptions", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.content[0].status").value("INCOMPLETE"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void list_wrongTenant_returns403() throws Exception {
        UUID otherMerchant = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/subscriptions", otherMerchant))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_customerIdFilter_passedToService() throws Exception {
        when(service.list(eq(merchantId), any(), eq(customerId), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/subscriptions", merchantId)
                        .param("customerId", customerId.toString()))
                .andExpect(status().isOk());

        verify(service).list(eq(merchantId), any(), eq(customerId), any());
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        CreateSubscriptionRequest request = new CreateSubscriptionRequest(
                customerId, "usd", BillingIntervalUnit.MONTH, 1, 0, null,
                List.of(new CreateSubscriptionRequest.SubscriptionItemRequest("Pro plan", 2900, 1)));
        when(service.create(eq(merchantId), any(), any()))
                .thenReturn(subscriptionResponse(merchantId, customerId, subscriptionId));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/subscriptions", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void create_missingCustomerId_returns400() throws Exception {
        // customerId is @NotNull — omitting it triggers validation failure
        String body = """
                {"currency":"usd","intervalUnit":"MONTH","intervalCount":1,
                 "items":[{"description":"Pro","amount":2900,"quantity":1}]}
                """;

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/subscriptions", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_unknownCustomer_returns400() throws Exception {
        CreateSubscriptionRequest request = new CreateSubscriptionRequest(
                customerId, "usd", BillingIntervalUnit.MONTH, 1, 0, null,
                List.of(new CreateSubscriptionRequest.SubscriptionItemRequest("Pro plan", 2900, 1)));
        when(service.create(eq(merchantId), any(), any()))
                .thenThrow(new IllegalArgumentException("Customer not found"));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/subscriptions", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Customer not found"));
    }

    @Test
    void get_unknownSubscription_returns400() throws Exception {
        when(service.get(merchantId, subscriptionId))
                .thenThrow(new IllegalArgumentException("Subscription not found"));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/subscriptions/{subscriptionId}",
                        merchantId, subscriptionId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Subscription not found"));
    }

    @Test
    void createCheckoutLink_returns201() throws Exception {
        CreateSubscriptionCheckoutLinkRequest request = new CreateSubscriptionCheckoutLinkRequest(
                Instant.now().plusSeconds(3600));
        when(service.createCheckoutLink(eq(merchantId), eq(subscriptionId), any()))
                .thenReturn(checkoutLinkResponse(merchantId, customerId, subscriptionId));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/subscriptions/{subscriptionId}/checkout-links",
                        merchantId, subscriptionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subscriptionId").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createCheckoutLink_canceledSubscription_returns409() throws Exception {
        when(service.createCheckoutLink(eq(merchantId), eq(subscriptionId), any()))
                .thenThrow(new IllegalStateException("Cannot create checkout link for a canceled subscription"));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/subscriptions/{subscriptionId}/checkout-links",
                        merchantId, subscriptionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Cannot create checkout link for a canceled subscription"));
    }

    private SubscriptionResponse subscriptionResponse(UUID merchantId, UUID customerId, UUID subscriptionId) {
        return new SubscriptionResponse(
                subscriptionId, merchantId, customerId, "TEST",
                SubscriptionStatus.INCOMPLETE.name(), "USD", "MONTH", 1,
                null, null, null, false, null, null, List.of(), Instant.now(), Instant.now());
    }

    private SubscriptionCheckoutLinkResponse checkoutLinkResponse(UUID merchantId, UUID customerId, UUID subscriptionId) {
        return new SubscriptionCheckoutLinkResponse(
                UUID.randomUUID(), subscriptionId, customerId,
                "sub_tok_test", "ACTIVE",
                "http://localhost:3000/subscribe/sub_tok_test",
                Instant.now().plusSeconds(3600), null, Instant.now(), Instant.now());
    }
}
