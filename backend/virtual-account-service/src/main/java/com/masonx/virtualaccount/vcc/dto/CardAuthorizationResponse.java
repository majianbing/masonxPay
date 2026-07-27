package com.masonx.virtualaccount.vcc.dto;

import com.masonx.virtualaccount.domain.po.CardAuthorization;

import java.math.BigDecimal;
import java.time.Instant;

public record CardAuthorizationResponse(
        String authId,
        String issuerId,
        String authorizationId,
        String cardId,
        String stan,
        String rrn,
        BigDecimal amount,
        String currency,
        String decision,
        String declineReason,
        String holdEventId,
        String status,
        BigDecimal releasedAmount,
        String releaseReason,
        Instant releasedAt,
        BigDecimal settledAmount,
        Instant settledAt,
        Instant createdAt
) {
    public static CardAuthorizationResponse from(CardAuthorization auth) {
        return new CardAuthorizationResponse(
                auth.authId(),
                auth.issuerId(),
                auth.authorizationId(),
                auth.cardId(),
                auth.stan(),
                auth.rrn(),
                auth.amount(),
                auth.currency(),
                auth.decision(),
                auth.declineReason(),
                auth.holdEventId(),
                auth.status().name(),
                auth.releasedAmount(),
                auth.releaseReason(),
                auth.releasedAt(),
                auth.settledAmount(),
                auth.settledAt(),
                auth.createdAt());
    }
}
