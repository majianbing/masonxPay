package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

public record PublicCheckoutRequest(
        @NotBlank String paymentMethodId,   // pm_xxx token from Stripe.js
        String customerName                 // optional, for display
) {}
