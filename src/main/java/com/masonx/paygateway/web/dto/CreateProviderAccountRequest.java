package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateProviderAccountRequest(
        @NotNull String provider,            // STRIPE | PAYPAL
        @NotBlank String mode,               // TEST | LIVE
        @NotBlank String label,
        @NotBlank String secretKey,          // plaintext — encrypted before storage, never returned
        String publishableKey,               // optional
        boolean primary
) {}
