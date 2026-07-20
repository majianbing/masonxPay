package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.constant.Direction;

import java.math.BigDecimal;

/**
 * All fields that feed into the HMAC-SHA256 balance signature for one entry.
 * Order and format are fixed — any change breaks existing chains.
 *
 * Canonical string (null-byte separated):
 *   ledgerAccountId \0 entrySeq \0 amount \0 asset \0 direction
 *   \0 balanceAfter \0 transactionId \0 prevSignature \0 signatureKeyId
 */
public record SignatureInput(
        String ledgerAccountId,
        long entrySeq,
        BigDecimal amount,
        String asset,
        Direction direction,
        BigDecimal balanceAfter,
        String transactionId,
        String prevSignature,
        String signatureKeyId
) {
    public static final String DEFAULT_SIGNATURE_KEY_ID = "default";

    public SignatureInput(String ledgerAccountId,
                          long entrySeq,
                          BigDecimal amount,
                          String asset,
                          Direction direction,
                          BigDecimal balanceAfter,
                          String transactionId,
                          String prevSignature) {
        this(ledgerAccountId, entrySeq, amount, asset, direction, balanceAfter,
                transactionId, prevSignature, DEFAULT_SIGNATURE_KEY_ID);
    }

    public SignatureInput {
        if (signatureKeyId == null || signatureKeyId.isBlank()) {
            signatureKeyId = DEFAULT_SIGNATURE_KEY_ID;
        }
    }

    public String canonical() {
        // stripTrailingZeros() normalises scale before toPlainString() so that
        // "10.00" (request body, scale 2) and "10.00000000" (DB NUMERIC(38,8), scale 8)
        // both produce "10" and never diverge between posting and verification.
        return ledgerAccountId + '\0'
                + entrySeq + '\0'
                + amount.stripTrailingZeros().toPlainString() + '\0'
                + asset + '\0'
                + direction.name() + '\0'
                + balanceAfter.stripTrailingZeros().toPlainString() + '\0'
                + transactionId + '\0'
                + prevSignature + '\0'
                + signatureKeyId;
    }
}
