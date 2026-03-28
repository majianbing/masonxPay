package com.masonx.paygateway.web.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.payment.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PaymentIntentResponse(
        UUID id,
        UUID merchantId,
        String mode,
        long amount,
        String currency,
        String status,
        String captureMethod,
        String resolvedProvider,
        UUID connectorAccountId,
        String connectorAccountLabel,
        String providerPaymentId,
        String idempotencyKey,
        Map<String, Object> metadata,
        String successUrl,
        String cancelUrl,
        String failureUrl,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        List<PaymentAttemptSummary> attempts
) {
    public record PaymentAttemptSummary(
            UUID id,
            String paymentMethodType,
            String status,
            String failureCode,
            String failureMessage,
            Instant createdAt
    ) {}

    public static PaymentIntentResponse from(PaymentIntent intent,
                                              List<PaymentRequest> attempts,
                                              ObjectMapper mapper,
                                              String connectorAccountLabel) {
        return new PaymentIntentResponse(
                intent.getId(),
                intent.getMerchantId(),
                intent.getMode().name(),
                intent.getAmount(),
                intent.getCurrency(),
                intent.getStatus().name(),
                intent.getCaptureMethod().name(),
                intent.getResolvedProvider() != null ? intent.getResolvedProvider().name() : null,
                intent.getConnectorAccountId(),
                connectorAccountLabel,
                intent.getProviderPaymentId(),
                intent.getIdempotencyKey(),
                deserialize(intent.getMetadata(), mapper),
                intent.getSuccessUrl(),
                intent.getCancelUrl(),
                intent.getFailureUrl(),
                intent.getExpiresAt(),
                intent.getCreatedAt(),
                intent.getUpdatedAt(),
                attempts.stream().map(a -> new PaymentAttemptSummary(
                        a.getId(), a.getPaymentMethodType(), a.getStatus().name(),
                        a.getFailureCode(), a.getFailureMessage(), a.getCreatedAt()
                )).toList()
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deserialize(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
