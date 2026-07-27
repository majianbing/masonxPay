package com.masonx.feeengine;

import java.math.BigDecimal;
import java.util.Map;

public record FeeComponent(
        String componentId,
        String name,
        FeeComponentType type,
        BigDecimal amount,
        String currency,
        Integer rateBps,
        String basisField,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        FeeVisibility visibility,
        Map<String, String> metadata
) {
    public FeeComponent {
        if (componentId == null || componentId.isBlank()) {
            throw new IllegalArgumentException("Fee component ID is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Fee component name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("Fee component type is required");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("Fee component visibility is required");
        }
        metadata = Map.copyOf(metadata != null ? metadata : Map.of());
    }

    public static FeeComponent fixed(String componentId,
                                     String name,
                                     BigDecimal amount,
                                     String currency,
                                     FeeVisibility visibility) {
        return new FeeComponent(componentId, name, FeeComponentType.FIXED, amount, currency,
                null, null, null, null, visibility, Map.of());
    }

    public static FeeComponent percentage(String componentId,
                                          String name,
                                          int rateBps,
                                          String basisField,
                                          FeeVisibility visibility) {
        return new FeeComponent(componentId, name, FeeComponentType.PERCENTAGE, null, null,
                rateBps, basisField, null, null, visibility, Map.of());
    }
}
