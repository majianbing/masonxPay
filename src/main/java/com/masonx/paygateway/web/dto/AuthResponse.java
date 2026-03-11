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
        List<OrgMembership> memberships
) {
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
