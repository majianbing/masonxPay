package com.masonx.feeengine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public record FeeLine(
        String ruleId,
        int ruleVersion,
        String ruleName,
        String componentId,
        String name,
        FeeVisibility visibility,
        String currency,
        BigDecimal basisAmount,
        BigDecimal rawCalculatedAmount,
        BigDecimal amount,
        RoundingMode roundingMode,
        int roundingScale,
        Map<String, String> metadata
) {
    public FeeLine {
        metadata = Map.copyOf(metadata != null ? metadata : Map.of());
    }
}
