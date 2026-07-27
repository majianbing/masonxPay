package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to create a new Virtual Credit Card backed by an existing WALLET account.
 *
 * <p>The card is created with zero balance. Use the fund endpoint to load money.
 */
public record CreateVccRequest(
        @NotBlank String merchantId,
        String mode,
        @NotBlank @Size(max = 128) String idempotencyKey,
        @NotBlank String ownerAccountId,  // existing WALLET account that will fund the card
        @NotBlank String programId,
        @NotBlank String cardholderId,
        @NotBlank String currency,
        @DecimalMin("0.01") BigDecimal spendingLimit,  // optional per-transaction cap; null = no cap
        LocalDate expiry                               // optional; defaults to 1 year from now
) {
    public CreateVccRequest(String merchantId,
                            String ownerAccountId,
                            String programId,
                            String cardholderId,
                            String currency,
                            BigDecimal spendingLimit,
                            LocalDate expiry) {
        this(merchantId, null, "legacy-create-card", ownerAccountId, programId, cardholderId, currency, spendingLimit, expiry);
    }
}
