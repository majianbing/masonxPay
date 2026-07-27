package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record IssuerAuthReversalRequest(
        @NotBlank String authorizationId,
        @NotBlank String reversalId,
        @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String currency
) {
}
