package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.billing.InvoiceStatus;
import com.masonx.paygateway.security.rbac.GatewayPermissionEvaluator;
import com.masonx.paygateway.service.billing.InvoicePaymentService;
import com.masonx.paygateway.service.billing.SubscriptionInvoiceService;
import com.masonx.paygateway.web.dto.InvoicePaymentResponse;
import com.masonx.paygateway.web.dto.InvoiceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class InvoiceControllerIntegrationTest {

    static {
        System.setProperty("csp.sentinel.log.dir",
                System.getProperty("java.io.tmpdir") + "/masonxpay-sentinel");
    }

    @Autowired MockMvc mockMvc;

    @MockBean SubscriptionInvoiceService invoiceService;
    @MockBean InvoicePaymentService paymentService;
    @MockBean GatewayPermissionEvaluator permissionEvaluator;

    private UUID merchantId;
    private UUID invoiceId;
    private UUID subscriptionId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        invoiceId = UUID.randomUUID();
        subscriptionId = UUID.randomUUID();
        when(permissionEvaluator.hasPermission(any(), eq((Serializable) merchantId), any(), any()))
                .thenReturn(true);
    }

    @Test
    void list_returns200WithInvoices() throws Exception {
        when(invoiceService.listAll(eq(merchantId), any(), any()))
                .thenReturn(List.of(invoiceResponse(invoiceId, subscriptionId)));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/invoices", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(invoiceId.toString()))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
    }

    @Test
    void list_wrongTenant_returns403() throws Exception {
        UUID other = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/merchants/{merchantId}/invoices", other))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_unknownInvoice_returns400() throws Exception {
        when(invoiceService.get(merchantId, invoiceId))
                .thenThrow(new IllegalArgumentException("Invoice not found"));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/invoices/{invoiceId}", merchantId, invoiceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invoice not found"));
    }

    @Test
    void pay_success_returns200() throws Exception {
        when(paymentService.pay(merchantId, invoiceId))
                .thenReturn(new InvoicePaymentResponse(
                        invoiceId, InvoiceStatus.PAID.name(), "ACTIVE",
                        UUID.randomUUID(), 1, true, null, null));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/invoices/{invoiceId}/pay",
                        merchantId, invoiceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.invoiceStatus").value("PAID"))
                .andExpect(jsonPath("$.subscriptionStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.attemptNumber").value(1));
    }

    @Test
    void pay_noDefaultPaymentMethod_returns409() throws Exception {
        when(paymentService.pay(merchantId, invoiceId))
                .thenThrow(new IllegalStateException("No active default payment method found for customer"));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/invoices/{invoiceId}/pay",
                        merchantId, invoiceId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("No active default payment method found for customer"));
    }

    @Test
    void pay_wrongTenant_returns403() throws Exception {
        UUID other = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/merchants/{merchantId}/invoices/{invoiceId}/pay", other, invoiceId))
                .andExpect(status().isForbidden());
    }

    private InvoiceResponse invoiceResponse(UUID invoiceId, UUID subscriptionId) {
        return new InvoiceResponse(invoiceId, UUID.randomUUID(), subscriptionId,
                "TEST", "OPEN", 2900, 0, "usd",
                Instant.now().minusSeconds(86400), Instant.now().plusSeconds(86400),
                Instant.now(), null, Instant.now(), null);
    }
}
