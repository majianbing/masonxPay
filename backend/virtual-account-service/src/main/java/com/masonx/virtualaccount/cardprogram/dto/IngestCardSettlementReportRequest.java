package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record IngestCardSettlementReportRequest(
        @NotBlank String merchantId,
        Mode mode,
        @NotBlank String programId,
        @NotBlank String reportRef,
        @NotNull LocalDate settlementDate,
        @NotBlank String currency,
        @NotEmpty List<@Valid Line> lines
) {
    public record Line(
            @NotBlank String railPaymentId,
            String issuerTransactionId,
            @NotBlank String movementType,
            @NotNull @Positive BigDecimal amount,
            @NotBlank String currency
    ) {
    }
}
