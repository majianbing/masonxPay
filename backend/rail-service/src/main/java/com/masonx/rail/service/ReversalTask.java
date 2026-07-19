package com.masonx.rail.service;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable view of a {@code rail_reversal_task} row joined with its parent
 * {@code rail_payment}. Carries everything {@link Iso8583ReversalSender} needs
 * to build a 0400 message without additional DB queries.
 */
public record ReversalTask(
        String     id,
        String     paymentId,
        int        attempts,
        int        maxAttempts,
        String     originalStan,
        String     originalRrn,
        String     network,
        Instant    originalTxTime,
        BigDecimal amount,
        String     currency,
        String     merchantId,
        String     cardTokenId,   // null for non-card payments; VA card settlement identity
        String     maskedPan      // null for non-card payments; display/audit metadata only
) {}
