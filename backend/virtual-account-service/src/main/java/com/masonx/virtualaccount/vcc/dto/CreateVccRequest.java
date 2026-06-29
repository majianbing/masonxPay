package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to create a new Virtual Credit Card backed by an existing WALLET account.
 *
 * <p>The card is created with zero balance. Use the fund endpoint to load money.
 */
public record CreateVccRequest(
        @NotBlank String merchantId,
        @NotBlank String ownerAccountId,  // existing WALLET account that will fund the card
        @NotBlank String currency,
        @DecimalMin("0.01") BigDecimal spendingLimit,  // optional per-transaction cap; null = no cap
        LocalDate expiry                               // optional; defaults to 1 year from now
) {
}
