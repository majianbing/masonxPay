package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateProviderAccountRequest(
        @NotNull  String provider,       // STRIPE | SQUARE | BRAINTREE | MOLLIE | SIMULATOR
        @NotBlank String mode,           // TEST | LIVE
        @NotBlank String label,
        boolean primary,
        int weight,                      // 1–100, default 1
        int fixedFeeCents,               // flat per-transaction fee in smallest currency unit (e.g. 30 = $0.30); default 0
        int rateBps,                     // percentage rate in basis points (e.g. 290 = 2.90%); default 0

        // ── Stripe ────────────────────────────────────────────────────────────
        String secretKey,                // sk_test_xxx / sk_live_xxx
        String publishableKey,           // pk_test_xxx / pk_live_xxx (optional)

        // ── Square ────────────────────────────────────────────────────────────
        String accessToken,              // EAAA…  (server-side auth)
        String applicationId,            // sandbox-sq0idb-… (client-side JS SDK)
        String locationId,               // L…  (identifies which Square location)

        // ── Braintree ─────────────────────────────────────────────────────────
        String btMerchantId,             // Braintree merchant ID (used client-side for client token requests)
        String btPublicKey,              // Braintree public key (server-side API auth)
        String btPrivateKey,             // Braintree private key (server-side API auth, encrypted at rest)

        // ── Mollie ────────────────────────────────────────────────────────────
        String mollieApiKey,             // test_xxx or live_xxx — server-side only, encrypted at rest

        // ── Mason Simulator ──────────────────────────────────────────────────
        Double simulatorSuccessRatePercent // 0-100 synthetic PSP success rate. TEST-only, no secrets.
) {}
