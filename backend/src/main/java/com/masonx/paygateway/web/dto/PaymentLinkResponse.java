package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.payment.PaymentLink;

import java.time.Instant;
import java.util.UUID;

public record PaymentLinkResponse(
        UUID id,
        String token,
        String title,
        String description,
        long amount,
        String currency,
        String mode,
        String status,
        String redirectUrl,
        String payUrl,        // full shareable URL
        Instant createdAt,
        Instant expiresAt
) {
    public static PaymentLinkResponse from(PaymentLink link, String payBaseUrl) {
        return new PaymentLinkResponse(
                link.getId(),
                link.getToken(),
                link.getTitle(),
                link.getDescription(),
                link.getAmount(),
                link.getCurrency().toUpperCase(),
                link.getMode().name(),
                link.getStatus().name(),
                link.getRedirectUrl(),
                payBaseUrl + "/pay/" + link.getToken(),
                link.getCreatedAt(),
                link.getExpiresAt()
        );
    }
}
