package com.masonx.feeengine;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record FeeContextSchema(Map<String, FeeContextField> fields) {
    public FeeContextSchema {
        fields = Map.copyOf(fields != null ? fields : Map.of());
    }

    public static FeeContextSchema of(Collection<FeeContextField> fields) {
        Map<String, FeeContextField> byName = new LinkedHashMap<>();
        for (FeeContextField field : fields) {
            if (byName.put(field.name(), field) != null) {
                throw new IllegalArgumentException("Duplicate fee context field: " + field.name());
            }
        }
        return new FeeContextSchema(byName);
    }

    public boolean contains(String name) {
        return fields.containsKey(name);
    }

    public FeeContextField require(String name) {
        FeeContextField field = fields.get(name);
        if (field == null) {
            throw new FeeRuleValidationException("Unknown fee context field: " + name);
        }
        return field;
    }

    public Set<String> fieldNames() {
        return fields.keySet();
    }
}
