package com.masonx.virtualaccount.domain.po;

import com.masonx.virtualaccount.domain.constant.CardAuthorizationStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One authorization decision for a VA-issued card, keyed by the issuer-minted
 * {@code authorizationId} scoped to {@code issuerId}. The stored decision is
 * the replay payload for duplicate deliveries of the same authorization.
 */
public record CardAuthorization(
        String authId,
        String issuerId,
        String authorizationId,
        String cardId,
        String stan,             // nullable audit metadata
        String rrn,              // nullable audit metadata
        BigDecimal amount,
        String currency,
        String decision,         // APPROVED / DECLINED
        String declineReason,    // null when approved
        String holdEventId,      // ledger source_event_id of the hold; null when no hold posted
        CardAuthorizationStatus status,
        Instant createdAt
) {
}
