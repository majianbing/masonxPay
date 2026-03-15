package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;

/**
 * Implemented by each payment provider (Stripe, Square, …).
 * Credentials are passed per-call so one service instance handles all merchants.
 */
public interface PaymentProviderService {
    PaymentProvider brand();
    ChargeResult charge(ChargeRequest request, ProviderCredentials creds);
    RefundResult  refund(RefundRequest request, ProviderCredentials creds);
}
