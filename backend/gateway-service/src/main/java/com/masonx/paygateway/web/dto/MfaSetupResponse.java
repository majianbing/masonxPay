package com.masonx.paygateway.web.dto;

/** Returned by POST /auth/mfa/setup — not yet active until /auth/mfa/confirm succeeds. */
public record MfaSetupResponse(
        String secret,    // Base32 — displayed as manual entry key
        String qrCodeUri  // otpauth://totp/... — encode into a QR image on the frontend
) {}
