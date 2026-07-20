package com.masonx.paygateway.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record VirtualAccountLedgerEntryResponse(
        String entryId,
        String transactionId,
        String direction,
        BigDecimal amount,
        String asset,
        BigDecimal balanceAfter,
        long entrySeq,
        LocalDate effectiveDate,
        String status,
        Instant createdAt
) {
}
