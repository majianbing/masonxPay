package com.masonx.paygateway.web.dto;

import java.time.Instant;

public record CreateSubscriptionCheckoutLinkRequest(
        Instant expiresAt
) {}
