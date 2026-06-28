package com.masonx.paygateway.projection;

import com.fasterxml.jackson.databind.JsonNode;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.projection.PaymentReadModel;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PaymentReadModelMapper {

    private PaymentReadModelMapper() {}

    public static void applyPaymentIntent(PaymentReadModel model, PaymentIntent intent, long refundedAmountSucceeded) {
        model.setPaymentIntentId(intent.getId());
        model.setMerchantId(intent.getMerchantId());
        model.setMode(intent.getMode().name());
        model.setAmount(intent.getAmount());
        model.setCurrency(intent.getCurrency());
        model.setStatus(intent.getStatus().name());
        model.setCaptureMethod(intent.getCaptureMethod() != null ? intent.getCaptureMethod().name() : null);
        model.setResolvedProvider(intent.getResolvedProvider() != null ? intent.getResolvedProvider().name() : null);
        model.setConnectorAccountId(intent.getConnectorAccountId());
        model.setProviderPaymentId(intent.getProviderPaymentId());
        model.setIdempotencyKey(intent.getIdempotencyKey());
        model.setOrderId(intent.getOrderId());
        model.setDescription(intent.getDescription());
        model.setBillingEmail(intent.getBillingDetails() != null ? intent.getBillingDetails().email() : null);
        model.setRefundedAmountSucceeded(refundedAmountSucceeded);
        model.setSourceCreatedAt(intent.getCreatedAt());
        model.setSourceUpdatedAt(intent.getUpdatedAt());
        model.setSearchText(searchText(model));
    }

    public static void applyPaymentPayload(PaymentReadModel model, JsonNode payload, UUID fallbackPaymentIntentId,
                                           UUID fallbackMerchantId) {
        UUID paymentIntentId = uuid(payload, "id").orElse(fallbackPaymentIntentId);
        UUID merchantId = uuid(payload, "merchantId").orElse(fallbackMerchantId);

        model.setPaymentIntentId(paymentIntentId);
        model.setMerchantId(merchantId);
        model.setMode(text(payload, "mode").orElse("TEST"));
        model.setAmount(payload.path("amount").asLong(0));
        model.setCurrency(text(payload, "currency").orElse("USD"));
        model.setStatus(text(payload, "status").orElse(null));
        model.setCaptureMethod(text(payload, "captureMethod").orElse(null));
        model.setResolvedProvider(text(payload, "resolvedProvider").orElse(null));
        model.setConnectorAccountId(uuid(payload, "connectorAccountId").orElse(null));
        model.setProviderPaymentId(text(payload, "providerPaymentId").orElse(null));
        model.setIdempotencyKey(text(payload, "idempotencyKey").orElse(null));
        model.setOrderId(text(payload, "orderId").orElse(null));
        model.setDescription(text(payload, "description").orElse(null));
        model.setBillingEmail(text(payload.path("billingDetails"), "email").orElse(null));
        // paymentMethodType lives at the top level on older events; on newer events it is
        // inside attempts[]. Fall back to the first attempt's value if not at top level.
        Optional<String> methodType = text(payload, "paymentMethodType");
        if (methodType.isEmpty()) {
            JsonNode attempts = payload.path("attempts");
            if (attempts.isArray() && !attempts.isEmpty()) {
                methodType = text(attempts.get(0), "paymentMethodType");
            }
        }
        methodType.ifPresent(model::setPaymentMethodType);
        model.setSourceCreatedAt(instant(payload, "createdAt").orElse(null));
        model.setSourceUpdatedAt(instant(payload, "updatedAt").orElse(null));
        model.setSearchText(searchText(model));
    }

    public static String searchText(PaymentReadModel model) {
        return Stream.of(
                        model.getPaymentIntentId(),
                        model.getProviderPaymentId(),
                        model.getOrderId(),
                        model.getDescription(),
                        model.getBillingEmail(),
                        model.getResolvedProvider(),
                        model.getLastRefundId())
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::toLowerCase)
                .collect(Collectors.joining(" "));
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        String text = value.asText();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static Optional<UUID> uuid(JsonNode node, String field) {
        return text(node, field).map(UUID::fromString);
    }

    private static Optional<Instant> instant(JsonNode node, String field) {
        return text(node, field).map(Instant::parse);
    }
}
