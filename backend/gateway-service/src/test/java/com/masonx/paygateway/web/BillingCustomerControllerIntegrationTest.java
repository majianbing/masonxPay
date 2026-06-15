package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.security.rbac.GatewayPermissionEvaluator;
import com.masonx.paygateway.service.billing.BillingCustomerService;
import com.masonx.paygateway.web.dto.AttachCustomerPaymentMethodRequest;
import com.masonx.paygateway.web.dto.BillingCustomerRequest;
import com.masonx.paygateway.web.dto.BillingCustomerResponse;
import com.masonx.paygateway.web.dto.CustomerPaymentMethodResponse;
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

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class BillingCustomerControllerIntegrationTest {

    static {
        System.setProperty("csp.sentinel.log.dir",
                System.getProperty("java.io.tmpdir") + "/masonxpay-sentinel");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean BillingCustomerService service;
    @MockBean GatewayPermissionEvaluator permissionEvaluator;

    private UUID merchantId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        // Grant access to merchantId by default; other UUIDs return false (Mockito default).
        when(permissionEvaluator.hasPermission(any(), eq((Serializable) merchantId), any(), any()))
                .thenReturn(true);
    }

    @Test
    void list_returns200WithCustomers() throws Exception {
        when(service.list(merchantId, ApiKeyMode.TEST))
                .thenReturn(List.of(customerResponse(merchantId, customerId, "TEST")));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/customers", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(customerId.toString()))
                .andExpect(jsonPath("$[0].mode").value("TEST"));
    }

    @Test
    void list_wrongTenant_returns403() throws Exception {
        UUID otherMerchant = UUID.randomUUID();
        // permissionEvaluator not stubbed for otherMerchant → returns false → 403

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/customers", otherMerchant))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_liveModeParam_passesLiveModeToService() throws Exception {
        when(service.list(merchantId, ApiKeyMode.LIVE)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/customers", merchantId)
                        .param("mode", "LIVE"))
                .andExpect(status().isOk());

        verify(service).list(merchantId, ApiKeyMode.LIVE);
    }

    @Test
    void get_unknownCustomer_returns400() throws Exception {
        when(service.get(eq(merchantId), any(), eq(customerId)))
                .thenThrow(new IllegalArgumentException("Customer not found"));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/customers/{customerId}", merchantId, customerId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Customer not found"));
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        BillingCustomerRequest request = new BillingCustomerRequest("buyer@example.com", "Test Buyer", null);
        when(service.create(eq(merchantId), any(), any()))
                .thenReturn(customerResponse(merchantId, customerId, "TEST"));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/customers", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("buyer@example.com"));
    }

    @Test
    void create_invalidEmail_returns400() throws Exception {
        BillingCustomerRequest request = new BillingCustomerRequest("not-an-email", "Test", null);

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/customers", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void attachPaymentMethod_crossMerchantInstrument_returns400() throws Exception {
        UUID instrumentId = UUID.randomUUID();
        AttachCustomerPaymentMethodRequest request = new AttachCustomerPaymentMethodRequest(instrumentId, true);
        when(service.attachPaymentMethod(eq(merchantId), any(), eq(customerId), any()))
                .thenThrow(new IllegalArgumentException("Instrument does not belong to this merchant"));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/customers/{customerId}/payment-methods",
                        merchantId, customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Instrument does not belong to this merchant"));
    }

    @Test
    void attachPaymentMethod_validRequest_returns201() throws Exception {
        UUID instrumentId = UUID.randomUUID();
        UUID methodId = UUID.randomUUID();
        AttachCustomerPaymentMethodRequest request = new AttachCustomerPaymentMethodRequest(instrumentId, true);
        when(service.attachPaymentMethod(eq(merchantId), any(), eq(customerId), any()))
                .thenReturn(paymentMethodResponse(methodId, merchantId, customerId, instrumentId));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/customers/{customerId}/payment-methods",
                        merchantId, customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentInstrumentId").value(instrumentId.toString()))
                .andExpect(jsonPath("$.defaultMethod").value(true));
    }

    @Test
    void detachPaymentMethod_unknownMethod_returns400() throws Exception {
        UUID methodId = UUID.randomUUID();
        when(service.detachPaymentMethod(eq(merchantId), any(), eq(customerId), eq(methodId)))
                .thenThrow(new IllegalArgumentException("Payment method not found"));

        mockMvc.perform(delete("/api/v1/merchants/{merchantId}/customers/{customerId}/payment-methods/{methodId}",
                        merchantId, customerId, methodId))
                .andExpect(status().isBadRequest());
    }

    private BillingCustomerResponse customerResponse(UUID merchantId, UUID customerId, String mode) {
        return new BillingCustomerResponse(
                customerId, merchantId, mode, "buyer@example.com", "Test Buyer",
                null, Instant.now(), Instant.now());
    }

    private CustomerPaymentMethodResponse paymentMethodResponse(UUID methodId, UUID merchantId,
                                                                UUID customerId, UUID instrumentId) {
        return new CustomerPaymentMethodResponse(
                methodId, merchantId, customerId, instrumentId,
                com.masonx.paygateway.domain.billing.CustomerPaymentMethodStatus.ACTIVE,
                true, Instant.now(), Instant.now(),
                "SIMULATOR", null, null, null, null);
    }
}
