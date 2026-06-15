package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.BillingDetails;

import java.util.UUID;

public record ReusablePaymentMethodSetupRequest(
        UUID merchantId,
        UUID customerId,
        UUID subscriptionId,
        UUID providerAccountId,
        String paymentMethodType,
        String providerPaymentMethodId,
        String existingProviderCustomerReference,
        String idempotencyKey,
        BillingDetails billingDetails,
        String returnUrl
) {}
