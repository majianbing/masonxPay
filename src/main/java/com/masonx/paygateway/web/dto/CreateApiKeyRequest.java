package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.apikey.ApiKeyType;
import jakarta.validation.constraints.NotNull;

public record CreateApiKeyRequest(
        @NotNull ApiKeyType type,
        String name
) {}
