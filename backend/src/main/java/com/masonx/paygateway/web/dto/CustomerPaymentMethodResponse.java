package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.billing.CustomerPaymentMethod;
import com.masonx.paygateway.domain.billing.CustomerPaymentMethodStatus;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;

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
        Instant updatedAt,
        // Instrument display fields — null when instrument cannot be loaded
        String provider,
        String cardBrand,
        String last4,
        Integer expiryMonth,
        Integer expiryYear
) {
    public static CustomerPaymentMethodResponse from(CustomerPaymentMethod method) {
        return from(method, null);
    }

    public static CustomerPaymentMethodResponse from(CustomerPaymentMethod method, PaymentInstrument instrument) {
        return new CustomerPaymentMethodResponse(
                method.getId(),
                method.getMerchantId(),
                method.getCustomerId(),
                method.getPaymentInstrumentId(),
                method.getStatus(),
                method.isDefaultMethod(),
                method.getCreatedAt(),
                method.getUpdatedAt(),
                instrument != null && instrument.getProvider() != null ? instrument.getProvider().name() : null,
                instrument != null ? instrument.getCardBrand() : null,
                instrument != null ? instrument.getLast4() : null,
                instrument != null ? instrument.getExpiryMonth() : null,
                instrument != null ? instrument.getExpiryYear() : null
        );
    }
}
