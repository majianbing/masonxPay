package com.masonx.paygateway.fee;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentProvider;

import java.time.Instant;
import java.util.UUID;

public record GatewayFeeAssessment(
        String assessmentId,
        UUID merchantId,
        ApiKeyMode mode,
        String eventType,
        UUID eventId,
        UUID paymentIntentId,
        UUID paymentRequestId,
        PaymentProvider provider,
        UUID connectorAccountId,
        String paymentMethodType,
        String scheduleId,
        int scheduleVersion,
        String contextJson,
        String matchedRulesJson,
        String visibleTotalsJson,
        String hiddenTotalsJson,
        Instant createdAt
) {
}
