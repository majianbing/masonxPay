package com.masonx.virtualaccount.ledger.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record TransactionDetailResponse(
        String transactionId,
        String entryType,
        String description,
        String paymentReferenceId,
        LocalDate effectiveDate,
        String status,
        String mode,
        String merchantId,
        Instant createdAt,
        List<LedgerEntryResponse> entries
) {
}
