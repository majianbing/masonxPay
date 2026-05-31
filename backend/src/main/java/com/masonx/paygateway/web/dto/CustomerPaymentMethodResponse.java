package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodStatus;

import java.time.Instant;
import java.util.UUID;

public record CustomerPaymentMethodResponse(
        UUID id,
        UUID merchantId,
        UUID customerId,
        UUID paymentInstrumentId,
        CustomerPaymentMethodStatus status,
        boolean defaultMethod,
        Instant createdAt,
        Instant updatedAt
) {
    public static CustomerPaymentMethodResponse from(CustomerPaymentMethod method) {
        return new CustomerPaymentMethodResponse(
                method.getId(),
                method.getMerchantId(),
                method.getCustomerId(),
                method.getPaymentInstrumentId(),
                method.getStatus(),
                method.isDefaultMethod(),
                method.getCreatedAt(),
                method.getUpdatedAt()
        );
    }
}
