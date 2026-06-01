package com.masonx.paygateway.web.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Public-safe connector descriptor sent to the subscription checkout page.
 * Contains only the credentials the browser JS SDK needs — no private keys.
 * Braintree requires a separate /braintree-client-token call due to its
 * dynamic server-generated token model.
 */
public record CheckoutConnectorInfo(
        String provider,
        UUID accountId,
        String clientKey,
        Map<String, String> clientConfig
) {}
