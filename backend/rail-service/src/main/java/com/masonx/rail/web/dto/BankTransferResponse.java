package com.masonx.rail.web.dto;

import com.masonx.rail.canonical.RailPaymentStatus;

/**
 * Response for {@code POST /v1/rail/bank-transfers}.
 *
 * <p>On success, {@code status} is {@code ACCEPTED} — settlement is async.
 * The {@code endToEndId} can be used to correlate subsequent notifications.
 */
public record BankTransferResponse(
        String railPaymentId,
        RailPaymentStatus status,
        String messageId,
        String endToEndId,
        String statusCode,   // pain.002 TxSts: ACCP, RJCT, etc.
        String failureReason
) {
}
