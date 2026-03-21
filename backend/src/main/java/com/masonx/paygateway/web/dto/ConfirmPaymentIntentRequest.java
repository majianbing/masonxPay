package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfirmPaymentIntentRequest(
        @NotBlank String paymentMethodId,
        String paymentMethodType    // defaults to "card" in service if null
) {}
