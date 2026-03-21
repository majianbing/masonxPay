package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.payment.Refund;

import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID paymentIntentId,
        UUID merchantId,
        long amount,
        String currency,
        String status,
        String reason,
        String providerRefundId,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
    public static RefundResponse from(Refund r) {
        return new RefundResponse(
                r.getId(),
                r.getPaymentIntentId(),
                r.getMerchantId(),
                r.getAmount(),
                r.getCurrency(),
                r.getStatus().name(),
                r.getReason() != null ? r.getReason().name() : null,
                r.getProviderRefundId(),
                r.getFailureReason(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
