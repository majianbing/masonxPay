package com.masonx.virtualaccount.cardprogram.dto;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardSettlementReportLineStatus;
import com.masonx.virtualaccount.domain.po.CardSettlementReportLine;

import java.math.BigDecimal;
import java.time.Instant;

public record CardSettlementReportLineResponse(
        String reportLineId,
        String reportId,
        String merchantId,
        Mode mode,
        String programId,
        String railPaymentId,
        String issuerTransactionId,
        String movementType,
        BigDecimal amount,
        String currency,
        String matchedClearingEventId,
        String matchedCardId,
        CardSettlementReportLineStatus status,
        BigDecimal mismatchAmount,
        String detail,
        Instant createdAt
) {
    public static CardSettlementReportLineResponse from(CardSettlementReportLine line) {
        return new CardSettlementReportLineResponse(
                line.reportLineId(),
                line.reportId(),
                line.merchantId(),
                line.mode(),
                line.programId(),
                line.railPaymentId(),
                line.issuerTransactionId(),
                line.movementType(),
                line.amount(),
                line.currency(),
                line.matchedClearingEventId(),
                line.matchedCardId(),
                line.status(),
                line.mismatchAmount(),
                line.detail(),
                line.createdAt());
    }
}
