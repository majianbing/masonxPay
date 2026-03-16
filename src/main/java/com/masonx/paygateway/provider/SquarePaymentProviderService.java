package com.masonx.paygateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.credentials.SquareCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Square payment provider — calls Square's REST API directly via RestClient.
 * No Square Java SDK dependency needed; the API is simple REST + JSON.
 *
 * Sandbox:    connect.squareupsandbox.com
 * Production: connect.squareup.com
 */
@Service
public class SquarePaymentProviderService implements PaymentProviderService {

    private static final Logger log = LoggerFactory.getLogger(SquarePaymentProviderService.class);
    private static final String SQUARE_VERSION = "2024-01-18";

    private final RestClient restClient;

    public SquarePaymentProviderService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.SQUARE;
    }

    @Override
    public ChargeResult charge(ChargeRequest req, ProviderCredentials creds) {
        if (!(creds instanceof SquareCredentials square)) {
            return new ChargeResult(false, null, null,
                    "connector_not_configured", "No active Square connector found.", false);
        }

        try {
            Map<String, Object> body = Map.of(
                    "source_id",       req.paymentMethodId(),
                    "idempotency_key", squareKey(req.idempotencyKey()),
                    "amount_money",    Map.of(
                            "amount",   req.amount(),
                            "currency", req.currency().toUpperCase()),
                    "location_id",     square.locationId()
            );

            JsonNode response = restClient.post()
                    .uri(square.baseUrl() + "/v2/payments")
                    .header("Authorization", "Bearer " + square.accessToken())
                    .header("Square-Version", SQUARE_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode payment = response != null ? response.path("payment") : null;
            if (payment == null || payment.isMissingNode()) {
                return new ChargeResult(false, null, null, "unexpected_response",
                        "No payment object in Square response", true);
            }

            String status = payment.path("status").asText("");
            boolean succeeded = "COMPLETED".equalsIgnoreCase(status);
            String paymentId = payment.path("id").asText(null);
            String responseJson = response.toString();

            if (!succeeded) {
                String errorCode = payment.path("delay_action").asText("card_declined");
                // Card-level failures are never retryable
                return new ChargeResult(false, paymentId, responseJson, errorCode,
                        "Payment status: " + status, false);
            }

            return new ChargeResult(true, paymentId, responseJson, null, null, false);

        } catch (HttpClientErrorException e) {
            log.error("Square charge failed: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            String code = parseSquareErrorCode(e.getResponseBodyAsString());
            // HTTP 4xx from Square is typically a card decline (non-retryable)
            return new ChargeResult(false, null, null, code, e.getMessage(), false);
        } catch (Exception e) {
            log.error("Square charge error", e);
            // Network / unexpected error — worth retrying on another connector
            return new ChargeResult(false, null, null, "gateway_error", e.getMessage(), true);
        }
    }

    @Override
    public RefundResult refund(RefundRequest req, ProviderCredentials creds) {
        if (!(creds instanceof SquareCredentials square)) {
            return new RefundResult(false, null, "No active Square connector found.");
        }

        try {
            Map<String, Object> body = Map.of(
                    "idempotency_key", squareKey("refund-" + req.refundId()),
                    "payment_id",      req.providerPaymentId(),
                    "amount_money",    Map.of(
                            "amount",   req.amount(),
                            "currency", "USD")   // Square refunds inherit currency from payment
            );

            JsonNode response = restClient.post()
                    .uri(square.baseUrl() + "/v2/refunds")
                    .header("Authorization", "Bearer " + square.accessToken())
                    .header("Square-Version", SQUARE_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode refund = response != null ? response.path("refund") : null;
            if (refund == null || refund.isMissingNode()) {
                return new RefundResult(false, null, "No refund object in Square response");
            }

            String status = refund.path("status").asText("");
            boolean succeeded = "COMPLETED".equalsIgnoreCase(status) || "PENDING".equalsIgnoreCase(status);
            return new RefundResult(succeeded, refund.path("id").asText(null),
                    succeeded ? null : "Refund status: " + status);

        } catch (HttpClientErrorException e) {
            log.error("Square refund failed: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            return new RefundResult(false, null, e.getMessage());
        } catch (Exception e) {
            log.error("Square refund error", e);
            return new RefundResult(false, null, e.getMessage());
        }
    }

    /**
     * Square requires idempotency_key ≤ 45 characters.
     * Derive a deterministic UUID (36 chars) from any key — collision-resistant, always in range.
     */
    private String squareKey(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String parseSquareErrorCode(String body) {
        try {
            // errors[0].code from Square error response
            int start = body.indexOf("\"code\":");
            if (start < 0) return "square_error";
            int valueStart = body.indexOf('"', start + 7) + 1;
            int valueEnd   = body.indexOf('"', valueStart);
            return body.substring(valueStart, valueEnd).toLowerCase();
        } catch (Exception e) {
            return "square_error";
        }
    }
}
