package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.merchant.Merchant;

import java.time.Instant;
import java.util.UUID;

public record MerchantResponse(
        UUID id,
        UUID organizationId,
        String name,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static MerchantResponse from(Merchant m) {
        return new MerchantResponse(
                m.getId(),
                m.getOrganizationId(),
                m.getName(),
                m.getStatus(),
                m.getCreatedAt(),
                m.getUpdatedAt()
        );
    }
}
