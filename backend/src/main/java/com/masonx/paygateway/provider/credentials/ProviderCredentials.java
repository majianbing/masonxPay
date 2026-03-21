package com.masonx.paygateway.provider.credentials;

import java.util.Map;

/**
 * Typed, provider-specific credentials loaded from a ProviderAccount.
 * Each provider implements this sealed interface with its own fields.
 */
public sealed interface ProviderCredentials
        permits StripeCredentials, SquareCredentials {

    /** Public identifier sent to the browser to bootstrap the provider's JS SDK. */
    String clientKey();

    /**
     * Extra key/value config the browser needs beyond clientKey.
     * Empty for Stripe; contains locationId for Square; etc.
     */
    default Map<String, String> clientConfig() {
        return Map.of();
    }
}
