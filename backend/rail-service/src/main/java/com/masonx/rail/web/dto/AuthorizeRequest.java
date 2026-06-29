package com.masonx.rail.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AuthorizeRequest(
        @NotBlank String merchantId,
        @NotBlank String idempotencyKey,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotBlank String currency,
        @NotBlank String testPan,
        @NotBlank String expiry,
        String network          // VISA_SIM (default) or MC_SIM — derived from PAN if omitted
) {
}
