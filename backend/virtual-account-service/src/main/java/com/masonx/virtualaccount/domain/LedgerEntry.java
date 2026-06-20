package com.masonx.virtualaccount.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable double-entry ledger row. Never updated or deleted after insert —
 * corrections are compensating entries with status REVERSED.
 *
 * entry_seq is a per-account monotonic counter used for HMAC chain ordering.
 * balance_after is a running snapshot; balance_signature chains each entry to
 * the previous one for tamper detection.
 */
public record LedgerEntry(
        String entryId,
        String transactionId,
        String accountId,
        Direction direction,
        BigDecimal amount,
        String asset,
        long entrySeq,
        BigDecimal balanceAfter,
        String balanceSignature,
        String sourceEventId,
        EntryStatus status,
        Instant createdAt
) {
}
