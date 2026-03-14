package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PreviewPaymentRequest(
        @NotNull @Min(50) long amount,      // in cents, min $0.50
        @NotBlank String currency,          // e.g. "usd"
        @NotBlank String testCard           // Stripe test PM token: pm_card_visa, pm_card_chargeDeclined, etc.
) {}
