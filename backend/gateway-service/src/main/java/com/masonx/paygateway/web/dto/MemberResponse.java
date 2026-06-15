package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.merchant.MerchantRole;
import com.masonx.paygateway.domain.merchant.MerchantUser;
import com.masonx.paygateway.domain.merchant.MerchantUserStatus;
import java.time.Instant;
import java.util.UUID;

public record MemberResponse(
        UUID id,
        UUID userId,
        String email,
        MerchantRole role,
        MerchantUserStatus status,
        Instant createdAt
) {
    public static MemberResponse from(MerchantUser mu) {
        return new MemberResponse(
                mu.getId(),
                mu.getUser().getId(),
                mu.getUser().getEmail(),
                mu.getRole(),
                mu.getStatus(),
                mu.getCreatedAt()
        );
    }
}
