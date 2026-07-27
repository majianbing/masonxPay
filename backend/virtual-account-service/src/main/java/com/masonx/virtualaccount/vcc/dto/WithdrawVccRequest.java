package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WithdrawVccRequest(
        @NotBlank String merchantId,
        String mode,
        @NotBlank String idempotencyKey,
        @NotNull @DecimalMin("0.01") BigDecimal amount
) {
    public WithdrawVccRequest(String merchantId, String idempotencyKey, BigDecimal amount) {
        this(merchantId, null, idempotencyKey, amount);
    }
}
