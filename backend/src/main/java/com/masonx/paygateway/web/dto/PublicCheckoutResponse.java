package com.masonx.paygateway.web.dto;

import java.util.UUID;

public record PublicCheckoutResponse(
        boolean        success,
        String         status,
        UUID           paymentIntentId,
        String         failureCode,
        String         failureMessage,
        String         redirectUrl,      // non-null when success && payment link has a redirect_url
        ProviderAction providerAction    // non-null when status = REQUIRES_ACTION (3DS / SCA pending)
) {
    /**
     * Describes the next action the customer must complete before the payment can be finalized.
     *
     * type:         "stripe_sdk"   — SDK calls stripe.handleNextAction({ clientSecret })
     *               "redirect_url" — SDK opens actionUrl in an iframe overlay
     * actionUrl:    redirect URL for "redirect_url" type; null for "stripe_sdk"
     * clientSecret: Stripe PI client_secret for "stripe_sdk" type; null otherwise
     */
    public record ProviderAction(String type, String actionUrl, String clientSecret) {}
}
