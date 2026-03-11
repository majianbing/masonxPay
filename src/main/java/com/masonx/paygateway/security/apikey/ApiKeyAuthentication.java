package com.masonx.paygateway.security.apikey;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.apikey.ApiKeyType;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.List;
import java.util.UUID;

/**
 * Authentication token set in the SecurityContext when a request is authenticated via API key.
 * Used by payment endpoints (as opposed to JWT-authenticated dashboard endpoints).
 */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final UUID apiKeyId;
    private final UUID merchantId;
    private final ApiKeyMode mode;
    private final ApiKeyType type;

    public ApiKeyAuthentication(UUID apiKeyId, UUID merchantId, ApiKeyMode mode, ApiKeyType type) {
        super(List.of());
        this.apiKeyId = apiKeyId;
        this.merchantId = merchantId;
        this.mode = mode;
        this.type = type;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() { return null; }

    @Override
    public Object getPrincipal() { return apiKeyId; }

    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getMerchantId() { return merchantId; }
    public ApiKeyMode getMode() { return mode; }
    public ApiKeyType getType() { return type; }
}
