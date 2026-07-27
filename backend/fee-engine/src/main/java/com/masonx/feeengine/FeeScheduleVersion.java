package com.masonx.feeengine;

import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

public record FeeScheduleVersion(
        String scheduleId,
        int version,
        String feeCurrency,
        int feeScale,
        RoundingMode roundingMode,
        List<FeeRule> rules,
        Map<String, String> metadata
) {
    public FeeScheduleVersion {
        if (scheduleId == null || scheduleId.isBlank()) {
            throw new IllegalArgumentException("Fee schedule ID is required");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("Fee schedule version must be positive");
        }
        if (feeCurrency == null || feeCurrency.isBlank()) {
            throw new IllegalArgumentException("Fee currency is required");
        }
        if (feeScale < 0) {
            throw new IllegalArgumentException("Fee scale must be non-negative");
        }
        roundingMode = roundingMode != null ? roundingMode : RoundingMode.HALF_UP;
        rules = List.copyOf(rules != null ? rules : List.of());
        metadata = Map.copyOf(metadata != null ? metadata : Map.of());
    }
}
