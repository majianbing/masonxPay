package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardSettlementReportStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record CardSettlementReport(
        String reportId,
        String merchantId,
        Mode mode,
        String programId,
        String issuerPartnerId,
        String reportRef,
        LocalDate settlementDate,
        String currency,
        BigDecimal totalAmount,
        BigDecimal matchedAmount,
        BigDecimal exceptionAmount,
        int lineCount,
        int exceptionCount,
        CardSettlementReportStatus status,
        Instant createdAt
) {
}
