package com.masonx.paygateway.web.dto;

import jakarta.validation.constraints.NotBlank;

/** Requires a live TOTP code or backup code to disable MFA. */
public record MfaDisableRequest(@NotBlank String code) {}
