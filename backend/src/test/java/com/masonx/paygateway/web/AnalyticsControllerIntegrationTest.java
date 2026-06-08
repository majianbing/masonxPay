package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.security.rbac.GatewayPermissionEvaluator;
import com.masonx.paygateway.service.AnalyticsService;
import com.masonx.paygateway.web.dto.AnalyticsResponse;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class AnalyticsControllerIntegrationTest {

    static {
        System.setProperty("csp.sentinel.log.dir",
                System.getProperty("java.io.tmpdir") + "/masonxpay-sentinel");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AnalyticsService analyticsService;
    @MockBean GatewayPermissionEvaluator permissionEvaluator;

    private UUID merchantId;

    private static AnalyticsResponse sampleResponse() {
        return new AnalyticsResponse(
                new AnalyticsResponse.Summary(200000L, 10L, 8L, 2L, 0.8),
                new AnalyticsResponse.RefundSummary(5000L, 2L, 0.025, 195000L),
                List.of(new AnalyticsResponse.BreakdownItem("SUCCEEDED", 8L, 200000L)),
                List.of(new AnalyticsResponse.TimeSeriesPoint("2026-06-01", 200000L, 10L, 5000L))
        );
    }

    @BeforeEach
    void setUp() {
        merchantId = UUID.randomUUID();
        when(permissionEvaluator.hasPermission(any(), eq((Serializable) merchantId), any(), any()))
                .thenReturn(true);
    }

    @Test
    void get_returns200WithFullResponse() throws Exception {
        when(analyticsService.getAnalytics(eq(merchantId), eq(ApiKeyMode.TEST),
                any(LocalDate.class), any(LocalDate.class), eq("status")))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/analytics", merchantId)
                        .param("mode", "TEST")
                        .param("groupBy", "status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalCount").value(10))
                .andExpect(jsonPath("$.summary.conversionRate").value(0.8))
                .andExpect(jsonPath("$.refundSummary.refundVolumeCents").value(5000))
                .andExpect(jsonPath("$.refundSummary.refundRate").value(0.025))
                .andExpect(jsonPath("$.refundSummary.netVolumeCents").value(195000))
                .andExpect(jsonPath("$.breakdown[0].key").value("SUCCEEDED"))
                .andExpect(jsonPath("$.timeSeries[0].date").value("2026-06-01"))
                .andExpect(jsonPath("$.timeSeries[0].refundVolumeCents").value(5000));
    }

    @Test
    void get_groupByReason_returns200() throws Exception {
        when(analyticsService.getAnalytics(eq(merchantId), any(), any(), any(), eq("reason")))
                .thenReturn(new AnalyticsResponse(
                        new AnalyticsResponse.Summary(0, 0, 0, 0, 0.0),
                        new AnalyticsResponse.RefundSummary(0, 0, 0.0, 0),
                        List.of(new AnalyticsResponse.BreakdownItem("CUSTOMER_REQUEST", 3L, 6000L)),
                        List.of()
                ));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/analytics", merchantId)
                        .param("groupBy", "reason"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.breakdown[0].key").value("CUSTOMER_REQUEST"));
    }

    @Test
    void get_wrongTenant_returns403() throws Exception {
        UUID other = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/merchants/{merchantId}/analytics", other))
                .andExpect(status().isForbidden());
    }

    @Test
    void get_invalidMode_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/merchants/{merchantId}/analytics", merchantId)
                        .param("mode", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_defaults_returns200() throws Exception {
        when(analyticsService.getAnalytics(any(), any(), any(), any(), any()))
                .thenReturn(new AnalyticsResponse(
                        new AnalyticsResponse.Summary(0, 0, 0, 0, 0.0),
                        new AnalyticsResponse.RefundSummary(0, 0, 0.0, 0),
                        List.of(), List.of()
                ));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}/analytics", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundSummary.refundCount").value(0));
    }
}
