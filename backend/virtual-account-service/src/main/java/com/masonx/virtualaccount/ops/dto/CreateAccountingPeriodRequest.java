package com.masonx.virtualaccount.ops.dto;

import com.masonx.common.tenant.Mode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateAccountingPeriodRequest(
        @NotNull Mode mode,
        @NotBlank String asset,
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd
) {
}
