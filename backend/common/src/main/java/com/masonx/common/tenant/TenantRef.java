package com.masonx.common.tenant;

import java.util.Objects;

/**
 * Tenant scope that travels on cross-service events and scopes queries:
 * {@code mode} + {@code merchantId} are always present; {@code orgId} is optional
 * (not every flow carries an organization context).
 */
public record TenantRef(Mode mode, OrgId orgId, MerchantId merchantId) {
    public TenantRef {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(merchantId, "merchant id");
    }
}
