package com.masonx.paygateway.provider;

import java.util.UUID;

public record ChargeRequest(
        UUID paymentIntentId,
        long amount,
        String currency,
        String paymentMethodType,
        String paymentMethodId,      // provider-specific payment method ref (e.g. Stripe pm_xxx)
        String idempotencyKey,
        String providerSecretKey     // merchant's own provider key, decrypted from ProviderAccount
) {}
