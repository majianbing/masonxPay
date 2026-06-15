package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.ShippingDetails;
import jakarta.validation.constraints.NotBlank;

public record ConfirmPaymentIntentRequest(
        @NotBlank String paymentMethodId,
        String paymentMethodType,   // defaults to "card" in service if null
        BillingDetails billingDetails,
        ShippingDetails shippingDetails
) {}
