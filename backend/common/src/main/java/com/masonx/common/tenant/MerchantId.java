package com.masonx.common.tenant;

import java.util.Objects;
import java.util.UUID;

/** Merchant identifier value object. */
public record MerchantId(UUID value) {
    public MerchantId {
        Objects.requireNonNull(value, "merchant id value");
    }
}
