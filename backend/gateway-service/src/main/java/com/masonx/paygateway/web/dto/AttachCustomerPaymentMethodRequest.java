package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AttachCustomerPaymentMethodRequest(
        @NotNull
        UUID paymentInstrumentId,

        boolean defaultMethod
) {
}
