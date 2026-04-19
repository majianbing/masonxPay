package com.masonx.paygateway.provider.credentials;

/**
 * Mollie credentials.
 *   apiKey  — test_xxx (sandbox) or live_xxx (production); server-side only, encrypted at rest.
 *             Mollie has no client-side publishable key — the checkout URL is returned by the API.
 *   sandbox — true when mode = TEST (derived from ApiKeyMode, not stored separately)
 */
public record MollieCredentials(
        String apiKey,
        boolean sandbox
) implements ProviderCredentials {

    @Override
    public String clientKey() {
        // Mollie has no publishable key. Return "mollie" so checkout-session
        // includes this provider in the picker (non-null = visible to browser SDK).
        return "mollie";
    }

    public String baseUrl() {
        return "https://api.mollie.com/v2";
    }
}
