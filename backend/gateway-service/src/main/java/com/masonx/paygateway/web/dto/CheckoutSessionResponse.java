package com.masonx.paygateway.web.dto;

import java.util.List;
import java.util.Map;

/**
 * Returned by GET /pub/checkout-session.
 * Tells the hosted checkout page which providers are available and how to initialize their JS SDKs.
 */
public record CheckoutSessionResponse(
        String merchantName,
        String mode,
        List<ProviderOption> providers,

        // Present when session is derived from a payment link
        Long amount,
        String currency,
        String title,
        String description
) {
    public record ProviderOption(
            String provider,               // STRIPE | SQUARE | …
            String clientKey,              // public key for the JS SDK (pk_xxx for Stripe, applicationId for Square)
            Map<String, String> clientConfig  // extra config the JS SDK needs (e.g., locationId for Square)
    ) {}
}
