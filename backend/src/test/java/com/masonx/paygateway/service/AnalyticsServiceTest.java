package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.RefundRepository;
import com.masonx.paygateway.web.dto.AnalyticsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private PaymentIntentRepository paymentRepo;
    private RefundRepository refundRepo;
    private AnalyticsService service;

    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        paymentRepo = mock(PaymentIntentRepository.class);
        refundRepo = mock(RefundRepository.class);
        service = new AnalyticsService(paymentRepo, refundRepo);

        when(paymentRepo.findGroupedByStatus(any(), any(), any(), any())).thenReturn(List.of());
        when(paymentRepo.findGroupedByProvider(any(), any(), any(), any())).thenReturn(List.of());
        when(paymentRepo.findGroupedByCurrency(any(), any(), any(), any())).thenReturn(List.of());
        when(paymentRepo.findRawForTimeSeries(any(), any(), any(), any())).thenReturn(List.of());
        when(refundRepo.findGroupedByStatus(any(), any(), any(), any())).thenReturn(List.of());
        when(refundRepo.findGroupedByReason(any(), any(), any(), any())).thenReturn(List.of());
        when(refundRepo.findRawForTimeSeries(any(), any(), any(), any())).thenReturn(List.of());
    }

    // ── Payment summary ───────────────────────────────────────────────────────

    @Test
    void emptyData_returnsZeroSummaryAndFilledTimeSeries() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to   = LocalDate.of(2026, 6, 3);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST, from, to, "status");

        assertThat(resp.summary().totalCount()).isZero();
        assertThat(resp.summary().conversionRate()).isZero();
        assertThat(resp.timeSeries()).hasSize(3);
        assertThat(resp.timeSeries().get(0).date()).isEqualTo("2026-06-01");
        assertThat(resp.timeSeries().get(2).date()).isEqualTo("2026-06-03");
    }

    @Test
    void statusBreakdown_computesPaymentSummaryCorrectly() {
        List<Object[]> statusRows = new java.util.ArrayList<>();
        statusRows.add(new Object[]{"SUCCEEDED", 80L, 160000L});
        statusRows.add(new Object[]{"FAILED",    15L, 0L});
        statusRows.add(new Object[]{"CANCELED",   5L, 0L});
        when(paymentRepo.findGroupedByStatus(any(), any(), any(), any())).thenReturn(statusRows);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "status");

        assertThat(resp.summary().totalCount()).isEqualTo(100);
        assertThat(resp.summary().succeededCount()).isEqualTo(80);
        assertThat(resp.summary().failedCount()).isEqualTo(15);
        assertThat(resp.summary().totalVolumeCents()).isEqualTo(160000);
        assertThat(resp.summary().conversionRate()).isEqualTo(0.8);

        assertThat(resp.breakdown()).hasSize(3);
        assertThat(resp.breakdown().get(0).key()).isEqualTo("SUCCEEDED");
    }

    @Test
    void connectorBreakdown_nullProviderLabeledUnknown() {
        List<Object[]> provRows = new java.util.ArrayList<>();
        provRows.add(new Object[]{null,     5L, 0L});
        provRows.add(new Object[]{"STRIPE", 20L, 40000L});
        when(paymentRepo.findGroupedByProvider(any(), any(), any(), any())).thenReturn(provRows);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "connector");

        assertThat(resp.breakdown().get(0).key()).isEqualTo("STRIPE");
        assertThat(resp.breakdown().get(1).key()).isEqualTo("Unknown");
    }

    @Test
    void rangeExceeding90Days_isClamped() {
        LocalDate to   = LocalDate.of(2026, 6, 8);
        LocalDate from = to.minusDays(200);

        service.getAnalytics(merchantId, ApiKeyMode.TEST, from, to, "status");

        Instant expectedFrom = to.minusDays(AnalyticsService.MAX_RANGE_DAYS)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        verify(paymentRepo).findGroupedByStatus(eq(merchantId), eq(ApiKeyMode.TEST),
                eq(expectedFrom), any());
    }

    // ── Refund summary ────────────────────────────────────────────────────────

    @Test
    void refundSummary_computesRateAndNetCorrectly() {
        List<Object[]> paymentRows = new java.util.ArrayList<>();
        paymentRows.add(new Object[]{"SUCCEEDED", 100L, 200000L});
        when(paymentRepo.findGroupedByStatus(any(), any(), any(), any())).thenReturn(paymentRows);

        List<Object[]> refundRows = new java.util.ArrayList<>();
        refundRows.add(new Object[]{"SUCCEEDED", 4L, 10000L});
        refundRows.add(new Object[]{"FAILED",    1L,   500L});
        when(refundRepo.findGroupedByStatus(any(), any(), any(), any())).thenReturn(refundRows);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "status");

        assertThat(resp.refundSummary().refundVolumeCents()).isEqualTo(10000);
        assertThat(resp.refundSummary().refundCount()).isEqualTo(4);
        assertThat(resp.refundSummary().refundRate()).isCloseTo(0.05, within(0.0001));
        assertThat(resp.refundSummary().netVolumeCents()).isEqualTo(190000);
    }

    @Test
    void refundSummary_rateIsZeroWhenNoPayments() {
        List<Object[]> rows = new java.util.ArrayList<>();
        rows.add(new Object[]{"SUCCEEDED", 2L, 5000L});
        when(refundRepo.findGroupedByStatus(any(), any(), any(), any())).thenReturn(rows);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "status");

        assertThat(resp.refundSummary().refundRate()).isZero();
        assertThat(resp.refundSummary().netVolumeCents()).isEqualTo(-5000);
    }

    // ── Reason breakdown ──────────────────────────────────────────────────────

    @Test
    void reasonBreakdown_returnsRefundsByReason() {
        List<Object[]> reasonRows = new java.util.ArrayList<>();
        reasonRows.add(new Object[]{"CUSTOMER_REQUEST", 10L, 20000L});
        reasonRows.add(new Object[]{"FRAUDULENT",        2L,  4000L});
        reasonRows.add(new Object[]{null,                1L,  1000L});
        when(refundRepo.findGroupedByReason(any(), any(), any(), any())).thenReturn(reasonRows);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "reason");

        assertThat(resp.breakdown()).hasSize(3);
        assertThat(resp.breakdown().get(0).key()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(resp.breakdown().get(1).key()).isEqualTo("FRAUDULENT");
        assertThat(resp.breakdown().get(2).key()).isEqualTo("Unspecified");

        // Reason breakdown must not call payment provider query
        verify(paymentRepo, never()).findGroupedByProvider(any(), any(), any(), any());
    }

    // ── Time series ───────────────────────────────────────────────────────────

    @Test
    void timeSeries_aggregatesPaymentAndRefundByDay() {
        Instant day1 = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        Instant day2 = LocalDate.of(2026, 6, 2).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

        List<Object[]> payRaw = new java.util.ArrayList<>();
        payRaw.add(new Object[]{day1, 5000L, "SUCCEEDED"});
        payRaw.add(new Object[]{day1,  500L, "FAILED"});
        payRaw.add(new Object[]{day2, 8000L, "SUCCEEDED"});
        when(paymentRepo.findRawForTimeSeries(any(), any(), any(), any())).thenReturn(payRaw);

        List<Object[]> refRaw = new java.util.ArrayList<>();
        refRaw.add(new Object[]{day1, 1000L, "SUCCEEDED"});
        refRaw.add(new Object[]{day2,  200L, "FAILED"});  // failed: should not count
        when(refundRepo.findRawForTimeSeries(any(), any(), any(), any())).thenReturn(refRaw);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), "status");

        assertThat(resp.timeSeries()).hasSize(2);

        var day1Point = resp.timeSeries().get(0);
        assertThat(day1Point.volumeCents()).isEqualTo(5000);
        assertThat(day1Point.count()).isEqualTo(2); // SUCCEEDED + FAILED
        assertThat(day1Point.refundVolumeCents()).isEqualTo(1000);

        var day2Point = resp.timeSeries().get(1);
        assertThat(day2Point.volumeCents()).isEqualTo(8000);
        assertThat(day2Point.refundVolumeCents()).isZero(); // only failed refund on day2
    }
}
