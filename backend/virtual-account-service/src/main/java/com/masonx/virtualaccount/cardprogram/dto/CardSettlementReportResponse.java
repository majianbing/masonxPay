package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardSettlementReportStatus;
import com.masonx.virtualaccount.domain.po.CardSettlementReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CardSettlementReportResponse(
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
        Instant createdAt,
        List<CardSettlementReportLineResponse> lines
) {
    public static CardSettlementReportResponse from(CardSettlementReport report,
                                                    List<CardSettlementReportLineResponse> lines) {
        return new CardSettlementReportResponse(
                report.reportId(),
                report.merchantId(),
                report.mode(),
                report.programId(),
                report.issuerPartnerId(),
                report.reportRef(),
                report.settlementDate(),
                report.currency(),
                report.totalAmount(),
                report.matchedAmount(),
                report.exceptionAmount(),
                report.lineCount(),
                report.exceptionCount(),
                report.status(),
                report.createdAt(),
                lines);
    }
}
