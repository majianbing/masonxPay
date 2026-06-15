package com.masonx.paygateway.provider.credentials;

import java.util.Map;

/**
 * Square credentials.
 *   accessToken   — server-side auth (encrypted at rest)
 *   applicationId — client-side, used to init Square.payments(applicationId, locationId)
 *   locationId    — identifies the Square location; used in both charge API and JS SDK
 *   sandbox       — true → use sandbox.web.squarecdn.com + connect.squareupsandbox.com
 */
public record SquareCredentials(
        String accessToken,
        String applicationId,
        String locationId,
        boolean sandbox
) implements ProviderCredentials {

    @Override
    public String clientKey() {
        return applicationId;
    }

    @Override
    public Map<String, String> clientConfig() {
        return locationId != null ? Map.of("locationId", locationId) : Map.of();
    }

    public String baseUrl() {
        return sandbox
                ? "https://connect.squareupsandbox.com"
                : "https://connect.squareup.com";
    }
}
