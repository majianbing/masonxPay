package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Sent by the hosted component after the customer enters their card.
 * Either linkToken or merchantId+mode must be present.
 *
 * providerPmId may be empty for redirect-only providers (e.g. Mollie) that have
 * no client-side token — the backend uses the payment intent ID to call the provider.
 */
public record TokenizeRequest(
        @NotBlank String provider,        // STRIPE | SQUARE | BRAINTREE | MOLLIE | …
        String providerPmId,              // raw PM token from provider JS SDK; empty for redirect-only providers

        // Payment-link flow: derive merchant + mode from the link
        String linkToken,

        // Merchant-SDK flow: explicit merchant context
        String merchantId,
        String mode                        // TEST | LIVE
) {}
