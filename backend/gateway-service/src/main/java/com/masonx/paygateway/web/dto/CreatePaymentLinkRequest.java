package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePaymentLinkRequest(
        @NotBlank String title,
        String description,
        @NotNull @Min(50) long amount,   // in cents, min $0.50
        @NotBlank String currency,
        String redirectUrl               // optional post-payment redirect
) {}
