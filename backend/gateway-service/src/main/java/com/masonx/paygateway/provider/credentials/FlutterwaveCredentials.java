package com.masonx.paygateway.provider.credentials;

/**
 * Flutterwave credentials.
 *   secretKey   - FLWSECK_TEST... / FLWSECK... server-side API auth, encrypted at rest.
 *   publicKey   - optional browser-safe key, stored in provider_config for future inline flows.
 *   webhookHash - optional webhook verification hash, encrypted at rest.
 *   sandbox     - true when mode = TEST, derived from ApiKeyMode.
 */
public record FlutterwaveCredentials(
        String secretKey,
        String publicKey,
        String webhookHash,
        boolean sandbox
) implements ProviderCredentials {

    @Override
    public String clientKey() {
        return publicKey != null && !publicKey.isBlank() ? publicKey : "flutterwave";
    }

    public String baseUrl() {
        return "https://api.flutterwave.com/v3";
    }
}
