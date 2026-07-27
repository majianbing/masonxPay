package com.masonx.feeengine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record FeeContext(Map<String, Object> values) {
    public FeeContext {
        values = Map.copyOf(values != null ? values : Map.of());
    }

    public static FeeContext of(Map<String, Object> values) {
        return new FeeContext(new LinkedHashMap<>(values));
    }

    public BigDecimal decimal(String fieldName) {
        Object value = values.get(fieldName);
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        throw new FeeEngineException("Fee context field is not decimal: " + fieldName);
    }
}
