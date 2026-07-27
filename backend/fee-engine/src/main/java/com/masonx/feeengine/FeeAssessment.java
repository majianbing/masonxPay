package com.masonx.feeengine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record FeeAssessment(
        String scheduleId,
        int scheduleVersion,
        List<FeeRuleMatch> matchedRules,
        List<FeeLine> lines,
        Map<String, BigDecimal> visibleTotals,
        Map<String, BigDecimal> hiddenTotals
) {
    public FeeAssessment {
        matchedRules = List.copyOf(matchedRules != null ? matchedRules : List.of());
        lines = List.copyOf(lines != null ? lines : List.of());
        visibleTotals = Map.copyOf(visibleTotals != null ? visibleTotals : Map.of());
        hiddenTotals = Map.copyOf(hiddenTotals != null ? hiddenTotals : Map.of());
    }

    static FeeAssessment of(String scheduleId,
                            int scheduleVersion,
                            List<FeeRuleMatch> matchedRules,
                            List<FeeLine> lines) {
        Map<String, BigDecimal> visible = new LinkedHashMap<>();
        Map<String, BigDecimal> hidden = new LinkedHashMap<>();
        for (FeeLine line : lines) {
            Map<String, BigDecimal> totals = line.visibility() == FeeVisibility.MERCHANT_VISIBLE
                    ? visible
                    : hidden;
            totals.merge(line.currency(), line.amount(), BigDecimal::add);
        }
        return new FeeAssessment(scheduleId, scheduleVersion, matchedRules, lines, visible, hidden);
    }
}
