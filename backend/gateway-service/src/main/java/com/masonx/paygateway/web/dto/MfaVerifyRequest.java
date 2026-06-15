package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Second step of login when MFA is enabled. */
public record MfaVerifyRequest(
        @NotBlank String mfaSessionToken,
        @NotBlank String code
) {}
