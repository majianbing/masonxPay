package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Transfers {@code amount} from the card's linked WALLET account to its PREPAID_CARD account. */
public record FundVccRequest(
        @NotBlank  String merchantId,
        String mode,
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotNull @DecimalMin("0.01") BigDecimal amount
) {
    public FundVccRequest(String merchantId, String idempotencyKey, BigDecimal amount) {
        this(merchantId, null, idempotencyKey, amount);
    }
}
