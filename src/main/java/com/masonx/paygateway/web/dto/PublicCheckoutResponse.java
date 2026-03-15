package com.masonx.paygateway.web.dto;

import java.util.UUID;

public record PublicCheckoutResponse(
        boolean success,
        String status,
        UUID paymentIntentId,
        String failureCode,
        String failureMessage,
        String redirectUrl      // non-null when success && payment link has a redirect_url
) {}
