package com.masonx.common.tenant;

import java.util.Objects;
import java.util.UUID;

/** Organization identifier value object. */
public record OrgId(UUID value) {
    public OrgId {
        Objects.requireNonNull(value, "org id value");
    }
}
