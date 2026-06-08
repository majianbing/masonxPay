package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.web.dto.AnalyticsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private PaymentIntentRepository repo;
    private AnalyticsService service;

    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PaymentIntentRepository.class);
        service = new AnalyticsService(repo);
        when(repo.findGroupedByStatus(any(), any(), any(), any())).thenReturn(List.of());
        when(repo.findGroupedByProvider(any(), any(), any(), any())).thenReturn(List.of());
        when(repo.findGroupedByCurrency(any(), any(), any(), any())).thenReturn(List.of());
        when(repo.findRawForTimeSeries(any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void emptyData_returnZeroSummaryAndFilledTimeSeries() {
        LocalDate from = LocalDate.of(2026, 6, 1);
        LocalDate to = LocalDate.of(2026, 6, 3);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST, from, to, "status");

        assertThat(resp.summary().totalCount()).isZero();
        assertThat(resp.summary().conversionRate()).isZero();
        // Time series should have one entry per day inclusive
        assertThat(resp.timeSeries()).hasSize(3);
        assertThat(resp.timeSeries().get(0).date()).isEqualTo("2026-06-01");
        assertThat(resp.timeSeries().get(2).date()).isEqualTo("2026-06-03");
    }

    @Test
    void statusBreakdown_computesSummaryCorrectly() {
        // [status, count, sum_amount]
        List<Object[]> statusRows = List.of(
                new Object[]{"SUCCEEDED", 80L, 160000L},
                new Object[]{"FAILED", 15L, 0L},
                new Object[]{"CANCELED", 5L, 0L}
        );
        when(repo.findGroupedByStatus(any(), any(), any(), any())).thenReturn(statusRows);

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "status");

        assertThat(resp.summary().totalCount()).isEqualTo(100);
        assertThat(resp.summary().succeededCount()).isEqualTo(80);
        assertThat(resp.summary().failedCount()).isEqualTo(15);
        assertThat(resp.summary().totalVolumeCents()).isEqualTo(160000);
        assertThat(resp.summary().conversionRate()).isEqualTo(0.8);

        // Breakdown by status should reflect the same rows sorted by count desc
        assertThat(resp.breakdown()).hasSize(3);
        assertThat(resp.breakdown().get(0).key()).isEqualTo("SUCCEEDED");
    }

    @Test
    void connectorBreakdown_nullProviderLabeledUnknown() {
        when(repo.findGroupedByProvider(any(), any(), any(), any())).thenReturn(List.of(
                new Object[]{null, 5L, 0L},
                new Object[]{"STRIPE", 20L, 40000L}
        ));

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31), "connector");

        assertThat(resp.breakdown()).hasSize(2);
        // Sorted by volumeCents desc: STRIPE first, Unknown second
        assertThat(resp.breakdown().get(0).key()).isEqualTo("STRIPE");
        assertThat(resp.breakdown().get(1).key()).isEqualTo("Unknown");
    }

    @Test
    void timeSeries_aggregatesSucceededVolumeByDay() {
        Instant day1 = LocalDate.of(2026, 6, 1).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        Instant day2 = LocalDate.of(2026, 6, 2).atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);

        when(repo.findRawForTimeSeries(any(), any(), any(), any())).thenReturn(List.of(
                new Object[]{day1, 1000L, "SUCCEEDED"},
                new Object[]{day1, 500L, "FAILED"},
                new Object[]{day2, 2000L, "SUCCEEDED"}
        ));

        AnalyticsResponse resp = service.getAnalytics(merchantId, ApiKeyMode.TEST,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2), "status");

        assertThat(resp.timeSeries()).hasSize(2);
        // Day 1: only SUCCEEDED amount counts
        assertThat(resp.timeSeries().get(0).volumeCents()).isEqualTo(1000);
        assertThat(resp.timeSeries().get(0).count()).isEqualTo(2); // both payments
        // Day 2:
        assertThat(resp.timeSeries().get(1).volumeCents()).isEqualTo(2000);
    }

    @Test
    void rangeExceeding90Days_isClamped() {
        LocalDate to = LocalDate.of(2026, 6, 8);
        LocalDate from = to.minusDays(200); // exceeds MAX

        service.getAnalytics(merchantId, ApiKeyMode.TEST, from, to, "status");

        // Verify the query was called with a clamped `from` (at most 90 days before `to`)
        Instant expectedFrom = to.minusDays(AnalyticsService.MAX_RANGE_DAYS)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        verify(repo).findGroupedByStatus(eq(merchantId), eq(ApiKeyMode.TEST),
                eq(expectedFrom), any());
    }
}
