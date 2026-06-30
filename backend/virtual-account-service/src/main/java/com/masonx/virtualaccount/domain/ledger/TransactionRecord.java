package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.TransactionType;

import java.time.Instant;
import java.time.LocalDate;

public record TransactionRecord(
        String transactionId,
        TransactionType entryType,
        String description,
        String paymentReferenceId,
        LocalDate effectiveDate,
        String status,
        Mode mode,
        String orgId,
        String merchantId,
        Instant createdAt
) {
}
