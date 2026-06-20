package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.Direction;

import java.math.BigDecimal;

/**
 * All fields that feed into the HMAC-SHA256 balance signature for one entry.
 * Order and format are fixed — any change breaks existing chains.
 *
 * Canonical string (null-byte separated):
 *   accountId \0 entrySeq \0 amount \0 direction \0 balanceAfter
 *   \0 frozenBalance \0 transactionId \0 prevSignature
 */
public record SignatureInput(
        String accountId,
        long entrySeq,
        BigDecimal amount,
        Direction direction,
        BigDecimal balanceAfter,
        BigDecimal frozenBalance,
        String transactionId,
        String prevSignature
) {
    public String canonical() {
        return accountId + '\0'
                + entrySeq + '\0'
                + amount.toPlainString() + '\0'
                + direction.name() + '\0'
                + balanceAfter.toPlainString() + '\0'
                + frozenBalance.toPlainString() + '\0'
                + transactionId + '\0'
                + prevSignature;
    }
}
