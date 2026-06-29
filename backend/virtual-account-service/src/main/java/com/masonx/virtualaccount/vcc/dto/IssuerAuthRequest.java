package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Authorization request from the card-network-sim for a BIN 999999 VA-issued card. */
public record IssuerAuthRequest(
        @NotBlank String maskedPan,               // first 6 + **** + last 4
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String currency,
        @NotBlank String stan,
        String rrn
) {
}
