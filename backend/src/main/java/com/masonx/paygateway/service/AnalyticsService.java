package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.web.dto.AnalyticsResponse;
import com.masonx.paygateway.web.dto.AnalyticsResponse.BreakdownItem;
import com.masonx.paygateway.web.dto.AnalyticsResponse.Summary;
import com.masonx.paygateway.web.dto.AnalyticsResponse.TimeSeriesPoint;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

@Service
public class AnalyticsService {

    static final int MAX_RANGE_DAYS = 90;

    private final PaymentIntentRepository paymentIntentRepository;

    public AnalyticsService(PaymentIntentRepository paymentIntentRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
    }

    public AnalyticsResponse getAnalytics(UUID merchantId, ApiKeyMode mode,
                                          LocalDate from, LocalDate to, String groupBy) {
        // Clamp range to MAX_RANGE_DAYS
        if (to.isBefore(from)) to = from;
        long daysBetween = from.until(to, java.time.temporal.ChronoUnit.DAYS);
        if (daysBetween > MAX_RANGE_DAYS) {
            from = to.minusDays(MAX_RANGE_DAYS);
        }

        Instant fromInstant = from.atStartOfDay(ZoneOffset.UTC).toInstant();
        // Include the full "to" day by using start of next day as exclusive upper bound
        Instant toInstant = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<Object[]> statusRows = paymentIntentRepository
                .findGroupedByStatus(merchantId, mode, fromInstant, toInstant);

        Summary summary = buildSummary(statusRows);
        List<BreakdownItem> breakdown = buildBreakdown(merchantId, mode, fromInstant, toInstant, groupBy, statusRows);

        List<Object[]> rawRows = paymentIntentRepository
                .findRawForTimeSeries(merchantId, mode, fromInstant, toInstant);
        List<TimeSeriesPoint> timeSeries = buildTimeSeries(rawRows, from, to);

        return new AnalyticsResponse(summary, breakdown, timeSeries);
    }

    private Summary buildSummary(List<Object[]> statusRows) {
        long totalCount = 0, succeededCount = 0, failedCount = 0, totalVolume = 0;
        for (Object[] row : statusRows) {
            String status = row[0].toString();
            long count = toLong(row[1]);
            long vol = row[2] != null ? toLong(row[2]) : 0L;
            totalCount += count;
            if ("SUCCEEDED".equals(status)) {
                succeededCount = count;
                totalVolume = vol;
            }
            if ("FAILED".equals(status)) {
                failedCount = count;
            }
        }
        double conversionRate = totalCount > 0 ? (double) succeededCount / totalCount : 0.0;
        return new Summary(totalVolume, totalCount, succeededCount, failedCount, conversionRate);
    }

    private List<BreakdownItem> buildBreakdown(UUID merchantId, ApiKeyMode mode,
                                               Instant from, Instant to,
                                               String groupBy, List<Object[]> statusRows) {
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
            default -> // "status"
                statusRows.stream()
                        .map(row -> new BreakdownItem(
                                row[0].toString(),
                                toLong(row[1]),
                                row[2] != null ? toLong(row[2]) : 0L))
                        .sorted(Comparator.comparingLong(BreakdownItem::count).reversed())
                        .toList();
        };
    }

    private List<TimeSeriesPoint> buildTimeSeries(List<Object[]> rows, LocalDate from, LocalDate to) {
        // Aggregate succeeded volume and total count per UTC calendar day
        Map<LocalDate, long[]> dayMap = new HashMap<>();
        for (Object[] row : rows) {
            Instant ts = (Instant) row[0];
            long amount = toLong(row[1]);
            String status = row[2].toString();
            LocalDate day = ts.atZone(ZoneOffset.UTC).toLocalDate();
            long[] agg = dayMap.computeIfAbsent(day, k -> new long[2]);
            if ("SUCCEEDED".equals(status)) agg[0] += amount;
            agg[1]++;
        }

        List<TimeSeriesPoint> result = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long[] agg = dayMap.getOrDefault(d, new long[2]);
            result.add(new TimeSeriesPoint(d.toString(), agg[0], agg[1]));
        }
        return result;
    }

    private static long toLong(Object val) {
        return val instanceof Number n ? n.longValue() : 0L;
    }
}
