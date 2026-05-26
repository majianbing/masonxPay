package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.BillingCustomer;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record BillingCustomerResponse(
        UUID id,
        UUID merchantId,
        String email,
        String name,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public static BillingCustomerResponse from(BillingCustomer customer, Map<String, String> metadata) {
        return new BillingCustomerResponse(
                customer.getId(),
                customer.getMerchantId(),
                customer.getEmail(),
                customer.getName(),
                metadata,
                customer.getCreatedAt(),
                customer.getUpdatedAt()
        );
    }
}
