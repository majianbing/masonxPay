package com.masonx.paygateway.web.dto;

public record PublicPaymentLinkInfo(
        String token,
        String title,
        String description,
        long amount,
        String currency,
        String mode,
        String merchantName,
        String publishableKey,   // merchant's Stripe publishable key — needed for Stripe.js
        boolean active           // false if INACTIVE or expired
) {}
