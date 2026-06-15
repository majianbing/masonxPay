package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;

/**
 * Provider capability for turning a customer-present payment token/nonce into
 * a provider-vaulted method that can be charged later for subscriptions.
 */
public interface ReusablePaymentMethodProviderService {
    PaymentProvider brand();

    ReusablePaymentMethodSetupResult setupReusablePaymentMethod(
            ReusablePaymentMethodSetupRequest request,
            ProviderCredentials creds);
}
