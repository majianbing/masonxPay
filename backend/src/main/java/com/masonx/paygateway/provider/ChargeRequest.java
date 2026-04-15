package com.masonx.paygateway.provider;

import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.ShippingDetails;

import java.util.UUID;

public record ChargeRequest(
        UUID paymentIntentId,
        long amount,
        String currency,
        String paymentMethodType,
        String paymentMethodId,   // provider-specific PM token (Stripe pm_xxx, Square sourceId, …)
        String idempotencyKey,
        BillingDetails billingDetails,
        ShippingDetails shippingDetails,
        CaptureMethod captureMethod   // null treated as AUTOMATIC
) {}
