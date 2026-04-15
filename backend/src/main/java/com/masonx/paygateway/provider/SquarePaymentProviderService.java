package com.masonx.paygateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.masonx.paygateway.domain.payment.Address;
import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.CaptureMethod;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source_id",       req.paymentMethodId());
            body.put("idempotency_key", squareKey(req.idempotencyKey()));
            body.put("amount_money",    Map.of(
                    "amount",   req.amount(),
                    "currency", req.currency().toUpperCase()));
            body.put("location_id",     square.locationId());

            // Buyer email for receipts and risk scoring
            if (req.billingDetails() != null && req.billingDetails().email() != null) {
                body.put("buyer_email_address", req.billingDetails().email());
            }

            // Billing address — used by Square for AVS and risk scoring
            Map<String, Object> billingAddr = buildSquareAddress(req.billingDetails());
            if (billingAddr != null) body.put("billing_address", billingAddr);

            // Manual capture: authorize now, settle later via captureAtProvider()
            if (req.captureMethod() == CaptureMethod.MANUAL) {
                body.put("autocomplete", false);
            }

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
            String code = parseSquareErrorCode(e.getResponseBodyAsString());
            log.error("Square charge failed: {} — {}", e.getStatusCode(), code);
            // HTTP 4xx from Square is typically a card decline (non-retryable)
            return new ChargeResult(false, null, null, code, e.getMessage(), false);
        } catch (Exception e) {
            log.error("Square charge error", e);
            // Network / unexpected error — worth retrying on another connector
            return new ChargeResult(false, null, null, "gateway_error", e.getMessage(), true);
        }
    }

    @Override
    public Optional<PaymentIntentStatus> syncStatus(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof SquareCredentials square)) return Optional.empty();
        try {
            JsonNode response = restClient.get()
                    .uri(square.baseUrl() + "/v2/payments/" + providerPaymentId)
                    .header("Authorization", "Bearer " + square.accessToken())
                    .header("Square-Version", SQUARE_VERSION)
                    .retrieve()
                    .body(JsonNode.class);

            String status = response != null
                    ? response.path("payment").path("status").asText("") : "";
            PaymentIntentStatus mapped = switch (status.toUpperCase()) {
                case "COMPLETED" -> PaymentIntentStatus.SUCCEEDED;
                case "CANCELED"  -> PaymentIntentStatus.CANCELED;
                case "FAILED"    -> PaymentIntentStatus.FAILED;
                default          -> null; // APPROVED / PENDING — still in-flight
            };
            return Optional.ofNullable(mapped);
        } catch (Exception e) {
            log.warn("Square syncStatus failed for {}: {}", providerPaymentId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean captureAtProvider(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof SquareCredentials square)) return false;
        try {
            JsonNode response = restClient.post()
                    .uri(square.baseUrl() + "/v2/payments/" + providerPaymentId + "/complete")
                    .header("Authorization", "Bearer " + square.accessToken())
                    .header("Square-Version", SQUARE_VERSION)
                    .retrieve()
                    .body(JsonNode.class);
            String status = response != null
                    ? response.path("payment").path("status").asText("") : "";
            return "COMPLETED".equalsIgnoreCase(status);
        } catch (Exception e) {
            log.warn("Square captureAtProvider failed for {}: {}", providerPaymentId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean cancelAtProvider(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof SquareCredentials square)) return false;
        try {
            JsonNode response = restClient.post()
                    .uri(square.baseUrl() + "/v2/payments/" + providerPaymentId + "/cancel")
                    .header("Authorization", "Bearer " + square.accessToken())
                    .header("Square-Version", SQUARE_VERSION)
                    .retrieve()
                    .body(JsonNode.class);

            String status = response != null
                    ? response.path("payment").path("status").asText("") : "";
            return "CANCELED".equalsIgnoreCase(status);
        } catch (Exception e) {
            log.warn("Square cancelAtProvider failed for {}: {}", providerPaymentId, e.getMessage());
            return false;
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
            String code = parseSquareErrorCode(e.getResponseBodyAsString());
            log.error("Square refund failed: {} — {}", e.getStatusCode(), code);
            return new RefundResult(false, null, e.getMessage());
        } catch (Exception e) {
            log.error("Square refund error", e);
            return new RefundResult(false, null, e.getMessage());
        }
    }

    private Map<String, Object> buildSquareAddress(BillingDetails bd) {
        if (bd == null) return null;
        Address addr = bd.address();
        Map<String, Object> map = new HashMap<>();
        if (bd.firstName() != null)              map.put("first_name", bd.firstName());
        if (bd.lastName() != null)               map.put("last_name", bd.lastName());
        if (addr != null) {
            if (addr.line1() != null)            map.put("address_line_1", addr.line1());
            if (addr.line2() != null)            map.put("address_line_2", addr.line2());
            if (addr.city() != null)             map.put("locality", addr.city());
            if (addr.state() != null)            map.put("administrative_district_level_1", addr.state());
            if (addr.postalCode() != null)       map.put("postal_code", addr.postalCode());
            if (addr.country() != null)          map.put("country", addr.country());
        }
        return map.isEmpty() ? null : map;
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
