package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateProviderAccountRequest(
        @NotNull  String provider,       // STRIPE | SQUARE | PAYPAL | …
        @NotBlank String mode,           // TEST | LIVE
        @NotBlank String label,
        boolean primary,
        int weight,                      // 1–100, default 1

        // ── Stripe ────────────────────────────────────────────────────────
        String secretKey,                // sk_test_xxx / sk_live_xxx
        String publishableKey,           // pk_test_xxx / pk_live_xxx (optional)

        // ── Square ────────────────────────────────────────────────────────
        String accessToken,              // EAAA…  (server-side auth)
        String applicationId,            // sandbox-sq0idb-… (client-side JS SDK)
        String locationId                // L…  (identifies which Square location)
) {}
