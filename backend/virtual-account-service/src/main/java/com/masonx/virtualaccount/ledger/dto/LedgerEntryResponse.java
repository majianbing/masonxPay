package com.masonx.virtualaccount.ledger.dto;

import com.masonx.virtualaccount.domain.po.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LedgerEntryResponse(
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
    public static LedgerEntryResponse from(LedgerEntry e) {
        return new LedgerEntryResponse(
                e.entryId(),
                e.transactionId(),
                e.direction().name(),
                e.amount(),
                e.asset(),
                e.balanceAfter(),
                e.entrySeq(),
                e.effectiveDate(),
                e.status().name(),
                e.createdAt());
    }
}
