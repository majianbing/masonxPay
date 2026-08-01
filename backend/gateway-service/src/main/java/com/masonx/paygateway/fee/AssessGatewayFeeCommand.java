package com.masonx.paygateway.fee;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentProvider;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AssessGatewayFeeCommand(
        UUID merchantId,
        ApiKeyMode mode,
        String eventType,
        UUID eventId,
        UUID paymentIntentId,
        UUID paymentRequestId,
        PaymentProvider provider,
        UUID connectorAccountId,
        String paymentMethodType,
        String currency,
        long amountMinor,
        Map<String, Object> context,
        Instant occurredAt
) {
}
