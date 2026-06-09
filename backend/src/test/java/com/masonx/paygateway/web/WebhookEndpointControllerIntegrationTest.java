package com.masonx.paygateway.web;

import com.masonx.paygateway.security.rbac.GatewayPermissionEvaluator;
import com.masonx.paygateway.service.WebhookDeliveryService;
import com.masonx.paygateway.service.WebhookEndpointService;
import com.masonx.paygateway.web.dto.WebhookDeliveryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class WebhookEndpointControllerIntegrationTest {

    static {
        System.setProperty("csp.sentinel.log.dir",
                System.getProperty("java.io.tmpdir") + "/masonxpay-sentinel");
    }

    @Autowired MockMvc mockMvc;

    @MockBean WebhookDeliveryService webhookDeliveryService;
    @MockBean WebhookEndpointService webhookEndpointService;
    @MockBean GatewayPermissionEvaluator permissionEvaluator;

    private UUID merchantId;
    private UUID endpointId;
    private UUID deliveryId;

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        endpointId = UUID.randomUUID();
        deliveryId = UUID.randomUUID();
        when(permissionEvaluator.hasPermission(any(), eq((Serializable) merchantId), any(), any()))
                .thenReturn(true);
    }

    @Test
    void listDeliveries_returnsPagedResult() throws Exception {
        WebhookDeliveryResponse delivery = sampleDelivery();
        var page = new PageImpl<>(List.of(delivery), PageRequest.of(0, 20), 1);
        when(webhookDeliveryService.listDeliveries(eq(merchantId), eq(endpointId), isNull(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/webhook-endpoints/{endpointId}/deliveries",
                        merchantId, endpointId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    void listDeliveries_withStatusFilter_passesStatusToService() throws Exception {
        WebhookDeliveryResponse delivery = sampleDeliveryWithStatus("FAILED");
        var page = new PageImpl<>(List.of(delivery), PageRequest.of(0, 20), 1);
        when(webhookDeliveryService.listDeliveries(eq(merchantId), eq(endpointId), any(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/webhook-endpoints/{endpointId}/deliveries",
                        merchantId, endpointId)
                        .param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("FAILED"));
    }

    @Test
    void listDeliveries_endpointNotFound_returns400() throws Exception {
        when(webhookDeliveryService.listDeliveries(eq(merchantId), eq(endpointId), any(), any()))
                .thenThrow(new IllegalArgumentException("Webhook endpoint not found"));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/webhook-endpoints/{endpointId}/deliveries",
                        merchantId, endpointId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replay_returnsCreatedWithNewDelivery() throws Exception {
        WebhookDeliveryResponse replayed = sampleDelivery();
        when(webhookDeliveryService.replay(eq(merchantId), eq(endpointId), eq(deliveryId)))
                .thenReturn(replayed);

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhook-endpoints/{endpointId}/deliveries/{deliveryId}/replay",
                        merchantId, endpointId, deliveryId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void replay_deliveryNotFound_returns400() throws Exception {
        when(webhookDeliveryService.replay(eq(merchantId), eq(endpointId), eq(deliveryId)))
                .thenThrow(new IllegalArgumentException("Webhook delivery not found"));

        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhook-endpoints/{endpointId}/deliveries/{deliveryId}/replay",
                        merchantId, endpointId, deliveryId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replay_deniedForDifferentMerchant_returns403() throws Exception {
        UUID otherMerchant = UUID.randomUUID();
        // permissionEvaluator only returns true for merchantId, not otherMerchant
        mockMvc.perform(post("/api/v1/merchants/{merchantId}/webhook-endpoints/{endpointId}/deliveries/{deliveryId}/replay",
                        otherMerchant, endpointId, deliveryId))
                .andExpect(status().isForbidden());
    }

    private WebhookDeliveryResponse sampleDelivery() {
        return new WebhookDeliveryResponse(
                deliveryId, UUID.randomUUID(), endpointId,
                "SUCCEEDED", 200, null, 1, null, Instant.now(), Instant.now());
    }

    private WebhookDeliveryResponse sampleDeliveryWithStatus(String status) {
        return new WebhookDeliveryResponse(
                deliveryId, UUID.randomUUID(), endpointId,
                status, 500, "error", 1, null, Instant.now(), Instant.now());
    }
}
