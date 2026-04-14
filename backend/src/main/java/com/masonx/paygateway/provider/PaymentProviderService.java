package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;

import java.util.Optional;

/**
 * Implemented by each payment provider (Stripe, Square, …).
 * Credentials are passed per-call so one service instance handles all merchants.
 */
public interface PaymentProviderService {
    PaymentProvider brand();
    ChargeResult charge(ChargeRequest request, ProviderCredentials creds);
    RefundResult  refund(RefundRequest request, ProviderCredentials creds);

    /**
     * Fetches the current status of a payment from the provider.
     * Returns the mapped local status when the provider gives a definitive answer
     * (SUCCEEDED, FAILED, or CANCELED), or empty when the payment is still in-flight.
     */
    Optional<PaymentIntentStatus> syncStatus(String providerPaymentId, ProviderCredentials creds);

    /**
     * Attempts to cancel/void the payment at the provider.
     * Returns true if the provider accepted the cancellation, false otherwise
     * (e.g. already settled, or a transient API error — caller should still
     * mark the local record CANCELED after logging the outcome).
     */
    boolean cancelAtProvider(String providerPaymentId, ProviderCredentials creds);
}
