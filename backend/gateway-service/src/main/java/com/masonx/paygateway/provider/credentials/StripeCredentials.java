package com.masonx.paygateway.provider.credentials;

/**
 * Stripe credentials.
 *   secretKey      — sk_test_xxx / sk_live_xxx  (server-side, encrypted at rest)
 *   publishableKey — pk_test_xxx / pk_live_xxx  (client-side, stored in provider_config)
 */
public record StripeCredentials(String secretKey, String publishableKey)
        implements ProviderCredentials {

    @Override
    public String clientKey() {
        return publishableKey;
    }
}
