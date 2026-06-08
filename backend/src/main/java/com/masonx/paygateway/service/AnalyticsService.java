package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.RefundRepository;
import com.masonx.paygateway.web.dto.AnalyticsResponse;
import com.masonx.paygateway.web.dto.AnalyticsResponse.BreakdownItem;
import com.masonx.paygateway.web.dto.AnalyticsResponse.RefundSummary;
import com.masonx.paygateway.web.dto.AnalyticsResponse.Summary;
import com.masonx.paygateway.web.dto.AnalyticsResponse.TimeSeriesPoint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AnalyticsService {

    static final int MAX_RANGE_DAYS = 90;

    private final PaymentIntentRepository paymentIntentRepository;
    private final RefundRepository refundRepository;

    public AnalyticsService(PaymentIntentRepository paymentIntentRepository,
                            RefundRepository refundRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.refundRepository = refundRepository;
    }

    public AnalyticsResponse getAnalytics(UUID merchantId, ApiKeyMode mode,
                                          LocalDate from, LocalDate to, String groupBy) {
        if (to.isBefore(from)) to = from;
        if (from.until(to, ChronoUnit.DAYS) > MAX_RANGE_DAYS) {
            from = to.minusDays(MAX_RANGE_DAYS);
        }

        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        // Payment aggregates
        List<Object[]> paymentStatusRows = paymentIntentRepository
                .findGroupedByStatus(merchantId, mode, fromInstant, toInstant);

        // Refund aggregates
        List<Object[]> refundStatusRows = refundRepository
                .findGroupedByStatus(merchantId, mode, fromInstant, toInstant);

        Summary summary = buildPaymentSummary(paymentStatusRows);
        RefundSummary refundSummary = buildRefundSummary(refundStatusRows, summary.totalVolumeCents());

        List<BreakdownItem> breakdown = buildBreakdown(
                merchantId, mode, fromInstant, toInstant, groupBy, paymentStatusRows, refundStatusRows);

        List<Object[]> paymentRaw = paymentIntentRepository
                .findRawForTimeSeries(merchantId, mode, fromInstant, toInstant);
        List<Object[]> refundRaw = refundRepository
                .findRawForTimeSeries(merchantId, mode, fromInstant, toInstant);
        List<TimeSeriesPoint> timeSeries = buildTimeSeries(paymentRaw, refundRaw, from, to);

        return new AnalyticsResponse(summary, refundSummary, breakdown, timeSeries);
    }

    private Summary buildPaymentSummary(List<Object[]> statusRows) {
        long totalCount = 0, succeededCount = 0, failedCount = 0, totalVolume = 0;
        for (Object[] row : statusRows) {
            String status = row[0].toString();
            long count = toLong(row[1]);
            long vol = row[2] != null ? toLong(row[2]) : 0L;
            totalCount += count;
            if ("SUCCEEDED".equals(status)) { succeededCount = count; totalVolume = vol; }
            if ("FAILED".equals(status)) { failedCount = count; }
        }
        double conversionRate = totalCount > 0 ? (double) succeededCount / totalCount : 0.0;
        return new Summary(totalVolume, totalCount, succeededCount, failedCount, conversionRate);
    }

    private RefundSummary buildRefundSummary(List<Object[]> refundStatusRows, long paymentVolumeCents) {
        long refundVolume = 0, refundCount = 0;
        for (Object[] row : refundStatusRows) {
            String status = row[0].toString();
            if ("SUCCEEDED".equals(status)) {
                refundVolume = row[2] != null ? toLong(row[2]) : 0L;
                refundCount = toLong(row[1]);
            }
        }
        double refundRate = paymentVolumeCents > 0
                ? (double) refundVolume / paymentVolumeCents
                : 0.0;
        return new RefundSummary(refundVolume, refundCount, refundRate,
                paymentVolumeCents - refundVolume);
    }

    private List<BreakdownItem> buildBreakdown(UUID merchantId, ApiKeyMode mode,
                                               Instant from, Instant to, String groupBy,
                                               List<Object[]> paymentStatusRows,
                                               List<Object[]> refundStatusRows) {
        return switch (groupBy.toLowerCase()) {
            case "connector" -> {
                List<Object[]> rows = paymentIntentRepository
                        .findGroupedByProvider(merchantId, mode, from, to);
                yield rows.stream()
                        .map(row -> new BreakdownItem(
                                row[0] == null ? "Unknown" : row[0].toString(),
                                toLong(row[1]),
                                row[2] != null ? toLong(row[2]) : 0L))
                        .sorted(Comparator.comparingLong(BreakdownItem::volumeCents).reversed())
                        .toList();
            }
            case "currency" -> {
                List<Object[]> rows = paymentIntentRepository
                        .findGroupedByCurrency(merchantId, mode, from, to);
                yield rows.stream()
                        .map(row -> new BreakdownItem(
                                (String) row[0],
                                toLong(row[1]),
                                row[2] != null ? toLong(row[2]) : 0L))
                        .sorted(Comparator.comparingLong(BreakdownItem::volumeCents).reversed())
                        .toList();
            }
            case "reason" -> {
                List<Object[]> rows = refundRepository
                        .findGroupedByReason(merchantId, mode, from, to);
                yield rows.stream()
                        .map(row -> new BreakdownItem(
                                row[0] == null ? "Unspecified" : row[0].toString(),
                                toLong(row[1]),
                                row[2] != null ? toLong(row[2]) : 0L))
                        .sorted(Comparator.comparingLong(BreakdownItem::count).reversed())
                        .toList();
            }
            default -> // "status"
                paymentStatusRows.stream()
                        .map(row -> new BreakdownItem(
                                row[0].toString(),
                                toLong(row[1]),
                                row[2] != null ? toLong(row[2]) : 0L))
                        .sorted(Comparator.comparingLong(BreakdownItem::count).reversed())
                        .toList();
        };
    }

    private List<TimeSeriesPoint> buildTimeSeries(List<Object[]> paymentRows,
                                                  List<Object[]> refundRows,
                                                  LocalDate from, LocalDate to) {
        // [0] = succeeded payment volume, [1] = total payment count, [2] = succeeded refund volume
        Map<LocalDate, long[]> dayMap = new HashMap<>();

        for (Object[] row : paymentRows) {
            Instant ts = (Instant) row[0];
            long amount = toLong(row[1]);
            String status = row[2].toString();
            LocalDate day = ts.atZone(ZoneOffset.UTC).toLocalDate();
            long[] agg = dayMap.computeIfAbsent(day, k -> new long[3]);
            if ("SUCCEEDED".equals(status)) agg[0] += amount;
            agg[1]++;
        }

        for (Object[] row : refundRows) {
            Instant ts = (Instant) row[0];
            long amount = toLong(row[1]);
            String status = row[2].toString();
            LocalDate day = ts.atZone(ZoneOffset.UTC).toLocalDate();
            long[] agg = dayMap.computeIfAbsent(day, k -> new long[3]);
            if ("SUCCEEDED".equals(status)) agg[2] += amount;
        }

        List<TimeSeriesPoint> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long[] agg = dayMap.getOrDefault(d, new long[3]);
            result.add(new TimeSeriesPoint(d.toString(), agg[0], agg[1], agg[2]));
        }
        return result;
    }

    private static long toLong(Object val) {
        return val instanceof Number n ? n.longValue() : 0L;
    }
}
