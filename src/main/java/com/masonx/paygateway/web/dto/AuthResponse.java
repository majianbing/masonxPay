package com.masonx.paygateway.web.dto;

import java.util.UUID;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        UUID merchantId
) {
    public AuthResponse(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, "Bearer", null);
    }

    public AuthResponse(String accessToken, String refreshToken, UUID merchantId) {
        this(accessToken, refreshToken, "Bearer", merchantId);
    }
}
