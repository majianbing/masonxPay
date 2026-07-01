package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.BillingCustomer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record BillingCustomerResponse(
        UUID id,
        String externalId,
        UUID merchantId,
        String mode,
        String email,
        String name,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public static BillingCustomerResponse from(BillingCustomer customer, Map<String, String> metadata) {
        return new BillingCustomerResponse(
                customer.getId(),
                customer.getExternalId(),
                customer.getMerchantId(),
                customer.getMode().name(),
                customer.getEmail(),
                customer.getName(),
                metadata,
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
    }
}
