package com.masonx.virtualaccount.domain.po;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardSettlementReportLineStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record CardSettlementReportLine(
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
}
