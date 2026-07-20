package com.masonx.virtualaccount.vcc.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Real-time authorization decision request from an issuer adapter.
 *
 * <p>{@code authorizationId} is minted by the issuer side, unique per distinct
 * authorization within that issuer, and MUST be reused when a delivery is
 * retried — the decision endpoint is idempotent on it and replays the stored
 * decision. The issuer identity itself is bound to the adapter endpoint, not
 * carried in the payload.
 *
 * <p>{@code cardTokenId} is the card identity resolved by the issuer/processor
 * side. {@code stan}/{@code rrn} are optional network audit metadata; no
 * decision logic may depend on them.
 */
public record IssuerAuthRequest(
        @NotBlank String authorizationId,
        @NotBlank String cardTokenId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String currency,
        String stan,
        String rrn
) {
}
