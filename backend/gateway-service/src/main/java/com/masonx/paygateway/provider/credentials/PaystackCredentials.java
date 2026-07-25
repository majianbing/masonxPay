package com.masonx.paygateway.provider.credentials;

/**
 * Paystack credentials.
 *   secretKey - sk_test_... / sk_live_... server-side API auth, encrypted at rest.
 *   publicKey - optional pk_test_... / pk_live_... browser-safe key for display/future inline flows.
 *   sandbox   - true when mode = TEST, derived from ApiKeyMode.
 */
public record PaystackCredentials(
        String secretKey,
        String publicKey,
        boolean sandbox
) implements ProviderCredentials {

    @Override
    public String clientKey() {
        return publicKey != null && !publicKey.isBlank() ? publicKey : "paystack";
    }

    public String baseUrl() {
        return "https://api.paystack.co";
    }
}
