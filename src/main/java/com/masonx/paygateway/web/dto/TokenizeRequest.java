package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Sent by the hosted component after the customer enters their card.
 * Either linkToken or merchantId+mode must be present.
 */
public record TokenizeRequest(
        @NotBlank String provider,        // STRIPE | ADYEN
        @NotBlank String providerPmId,    // raw PM token from provider JS SDK

        // Payment-link flow: derive merchant + mode from the link
        String linkToken,

        // Merchant-SDK flow: explicit merchant context
        String merchantId,
        String mode                        // TEST | LIVE
) {}
