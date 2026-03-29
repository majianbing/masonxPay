package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Verifies the scanned TOTP code to activate MFA. */
public record MfaConfirmRequest(@NotBlank String code) {}
