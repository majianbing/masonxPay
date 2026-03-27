package com.masonx.paygateway.provider.credentials;

/**
 * Braintree credentials.
 *
 * encrypted_credentials — {"publicKey": "...", "privateKey": "..."}
 * provider_config       — {"merchantId": "..."}
 *
 * Note: Braintree uses dynamic client tokens generated server-side, not a static publishable key.
 * clientKey() returns merchantId so the frontend knows which Braintree account to request a
 * client token for. The frontend must call a separate /client-token endpoint to bootstrap the
 * Drop-in UI.
 */
public record BraintreeCredentials(
        String merchantId,
        String publicKey,
        String privateKey,
        boolean sandbox
) implements ProviderCredentials {

    @Override
    public String clientKey() {
        return merchantId;
    }
}
