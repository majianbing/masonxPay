package com.masonx.rail.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /v1/rail/bank-transfers}.
 *
 * <p>Supports SEPA and FedNow simulator rails.
 * {@code network} defaults to SEPA_SIM when omitted.
 */
public record BankTransferRequest(
        @NotBlank String merchantId,
        @NotBlank String idempotencyKey,

        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,

        @NotBlank String creditorIban,
        @NotBlank String creditorName,
        @NotBlank String debtorIban,
        @NotBlank String debtorName,

        /** Optional: SEPA_SIM (default) or FEDNOW_SIM. */
        String network
) {
}
