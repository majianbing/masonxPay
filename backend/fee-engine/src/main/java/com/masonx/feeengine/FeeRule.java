package com.masonx.feeengine;

import java.util.List;
import java.util.Map;

public record FeeRule(
        String ruleId,
        int ruleVersion,
        String name,
        int priority,
        String matchExpression,
        List<FeeComponent> components,
        FeeVisibility visibility,
        boolean stopProcessing,
        Map<String, String> metadata
) {
    public FeeRule {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("Fee rule ID is required");
        }
        if (ruleVersion <= 0) {
            throw new IllegalArgumentException("Fee rule version must be positive");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Fee rule name is required");
        }
        if (matchExpression == null || matchExpression.isBlank()) {
            throw new IllegalArgumentException("Fee rule match expression is required");
        }
        components = List.copyOf(components != null ? components : List.of());
        metadata = Map.copyOf(metadata != null ? metadata : Map.of());
    }
}
