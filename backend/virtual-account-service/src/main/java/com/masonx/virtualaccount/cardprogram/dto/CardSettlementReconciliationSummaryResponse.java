package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CardSettlementReconciliationSummaryResponse(
        String merchantId,
        Mode mode,
        String programId,
        LocalDate settlementDate,
        String currency,
        BigDecimal issuerReportAmount,
        BigDecimal matchedReportAmount,
        BigDecimal clearingAmount,
        BigDecimal ledgerPostedAmount,
        BigDecimal exceptionAmount,
        BigDecimal clearingDelta,
        BigDecimal ledgerDelta,
        int lineCount,
        int exceptionCount,
        String status
) {
}
