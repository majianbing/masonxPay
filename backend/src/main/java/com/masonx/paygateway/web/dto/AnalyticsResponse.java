package com.masonx.paygateway.web.dto;

import java.util.List;

public record AnalyticsResponse(
        Summary summary,
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

    public record BreakdownItem(
            String key,
            long count,
            long volumeCents
    ) {}

    public record TimeSeriesPoint(
            String date,       // yyyy-MM-dd
            long volumeCents,
            long count
    ) {}
}
