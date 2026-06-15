package com.masonx.paygateway.web.dto;

import java.util.List;

public record AnalyticsResponse(
        Summary summary,
        RefundSummary refundSummary,
        List<BreakdownItem> breakdown,
        List<TimeSeriesPoint> timeSeries
) {
    public record Summary(
            long totalVolumeCents,
            long totalCount,
            long succeededCount,
            long failedCount,
            double conversionRate
    ) {}

    public record RefundSummary(
            long refundVolumeCents,
            long refundCount,
            double refundRate,     // refundVolumeCents / totalVolumeCents; 0.0 when no payments
            long netVolumeCents    // totalVolumeCents - refundVolumeCents
    ) {}

    public record BreakdownItem(
            String key,
            long count,
            long volumeCents
    ) {}

    public record TimeSeriesPoint(
            String date,             // yyyy-MM-dd
            long volumeCents,        // succeeded payment volume
            long count,              // all payment count
            long refundVolumeCents   // succeeded refund volume
    ) {}
}
