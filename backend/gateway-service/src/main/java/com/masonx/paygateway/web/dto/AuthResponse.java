package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.organization.OrganizationRole;

import java.util.List;
import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        UUID userId,
        String email,
        List<OrgMembership> memberships,
        /** True when MFA verification is still required — all token fields will be null. */
        boolean mfaRequired,
        /** Short-lived JWT (5 min) exchanged at /auth/mfa/verify. Null on full responses. */
        String mfaSessionToken,
        /** Whether this user has MFA enabled. Populated on full auth responses. */
        boolean mfaEnabled
) {
    /** Factory for the intermediate MFA challenge response. */
    public static AuthResponse mfaPending(String mfaSessionToken) {
        return new AuthResponse(null, null, null, null, null, null, true, mfaSessionToken, true);
    }

    public record OrgMembership(
            UUID organizationId,
            String organizationName,
            OrganizationRole orgRole,
            List<MerchantMembership> merchants
    ) {}

    public record MerchantMembership(
            UUID merchantId,
            String merchantName,
            String role
    ) {}
}
