package com.masonx.feeengine;

public record FeeContextField(
        String name,
        FeeFieldType type,
        boolean required
) {
    public FeeContextField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Fee context field name is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("Fee context field type is required");
        }
    }
}
