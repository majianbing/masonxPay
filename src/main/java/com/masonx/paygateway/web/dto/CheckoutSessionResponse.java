package com.masonx.paygateway.web.dto;

import java.util.List;

/**
 * Returned by GET /pub/checkout-session.
 * Tells the hosted component which provider brands are available and their publishable keys.
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
            String provider,          // STRIPE | ADYEN
            String publishableKey     // provider's publishable/client key for JS SDK
    ) {}
}
