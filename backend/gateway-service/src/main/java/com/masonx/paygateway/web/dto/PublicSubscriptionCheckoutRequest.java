package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

public record PublicSubscriptionCheckoutRequest(
        @NotBlank String gatewayToken
) {}
