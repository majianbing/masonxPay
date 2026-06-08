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
 *
 * Sandbox:    connect.squareupsandbox.com
 * Production: connect.squareup.com
 */
@Service
public class SquarePaymentProviderService
        extends AbstractPaymentProviderService<SquareCredentials>
        implements ReusablePaymentMethodProviderService {

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
    protected Class<SquareCredentials> credentialsType() {
        return SquareCredentials.class;
    }

    @Override
    protected ChargeResult sendCharge(ChargeRequest req, SquareCredentials square) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("source_id",       req.paymentMethodId());
            body.put("idempotency_key", squareKey(req.idempotencyKey()));
            body.put("amount_money",    Map.of(
                    "amount",   req.amount(),
                    "currency", req.currency().toUpperCase()));
            body.put("location_id",     square.locationId());
            if (req.providerCustomerReference() != null && !req.providerCustomerReference().isBlank()) {
                body.put("customer_id", req.providerCustomerReference());
            }
            if (req.billingDetails() != null && req.billingDetails().email() != null) {
                body.put("buyer_email_address", req.billingDetails().email());
            }
            Map<String, Object> billingAddr = buildSquareAddress(req.billingDetails());
            if (billingAddr != null) body.put("billing_address", billingAddr);
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
                        "No payment object in Square response", true, false, null, null, null);
            }

            String status      = payment.path("status").asText("");
            boolean succeeded  = "COMPLETED".equalsIgnoreCase(status);
            String paymentId   = payment.path("id").asText(null);
            String responseJson = response.toString();

            if (!succeeded) {
                String errorCode = payment.path("delay_action").asText("card_declined");
                return new ChargeResult(false, paymentId, responseJson, errorCode,
                        "Payment status: " + status, false, false, null, null, null);
            }
            return new ChargeResult(true, paymentId, responseJson, null, null, false, false, null, null, null);

        } catch (HttpClientErrorException e) {
            String code = parseSquareErrorCode(e.getResponseBodyAsString());
            log.error("Square charge failed: {} — {}", e.getStatusCode(), code);
            return new ChargeResult(false, null, null, code, e.getMessage(), false, false, null, null, null);
        } catch (Exception e) {
            log.error("Square charge error", e);
            return new ChargeResult(false, null, null, "gateway_error", e.getMessage(), true, false, null, null, null);
        }
    }

    @Override
    protected RefundResult sendRefund(RefundRequest req, SquareCredentials square) {
        try {
            Map<String, Object> body = Map.of(
                    "idempotency_key", squareKey("refund-" + req.refundId()),
                    "payment_id",      req.providerPaymentId(),
                    "amount_money",    Map.of("amount", req.amount(), "currency", "USD")
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

    @Override
    protected Optional<PaymentIntentStatus> sendSyncStatus(String providerPaymentId, SquareCredentials square) {
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
                default          -> null;
            };
            return Optional.ofNullable(mapped);
        } catch (Exception e) {
            log.warn("Square syncStatus failed for {}: {}", providerPaymentId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    protected boolean sendCapture(String providerPaymentId, SquareCredentials square) {
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
    protected boolean sendCancel(String providerPaymentId, SquareCredentials square) {
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

    // ── ReusablePaymentMethodProviderService ──────────────────────────────────

    @Override
    public ReusablePaymentMethodSetupResult setupReusablePaymentMethod(
            ReusablePaymentMethodSetupRequest request, ProviderCredentials creds) {
        if (!(creds instanceof SquareCredentials square)) {
            return ReusablePaymentMethodSetupResult.failed(
                    "connector_not_configured", "No active Square connector found.", false);
        }
        if (request.providerPaymentMethodId() == null || request.providerPaymentMethodId().isBlank()) {
            return ReusablePaymentMethodSetupResult.failed(
                    "missing_payment_method", "Square reusable setup requires a Web Payments SDK source id.", false);
        }

        try {
            String customerId = request.existingProviderCustomerReference();
            if (customerId == null || customerId.isBlank()) {
                Map<String, Object> customerBody = new LinkedHashMap<>();
                if (request.billingDetails() != null) {
                    if (request.billingDetails().email() != null)
                        customerBody.put("email_address", request.billingDetails().email());
                    if (request.billingDetails().phone() != null)
                        customerBody.put("phone_number", request.billingDetails().phone());
                    if (request.billingDetails().firstName() != null)
                        customerBody.put("given_name", request.billingDetails().firstName());
                    if (request.billingDetails().lastName() != null)
                        customerBody.put("family_name", request.billingDetails().lastName());
                }
                customerBody.put("reference_id", request.customerId().toString());
                customerBody.put("idempotency_key", squareKey(request.idempotencyKey() + "-customer"));

                JsonNode customerResponse = restClient.post()
                        .uri(square.baseUrl() + "/v2/customers")
                        .header("Authorization", "Bearer " + square.accessToken())
                        .header("Square-Version", SQUARE_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(customerBody)
                        .retrieve()
                        .body(JsonNode.class);
                customerId = customerResponse != null
                        ? customerResponse.path("customer").path("id").asText(null) : null;
            }
            if (customerId == null || customerId.isBlank()) {
                return ReusablePaymentMethodSetupResult.failed(
                        "customer_create_failed", "Square did not return a customer id.", true);
            }

            Map<String, Object> cardBody = new LinkedHashMap<>();
            cardBody.put("idempotency_key", squareKey(request.idempotencyKey() + "-card"));
            cardBody.put("source_id", request.providerPaymentMethodId());
            cardBody.put("card", Map.of("customer_id", customerId));

            JsonNode cardResponse = restClient.post()
                    .uri(square.baseUrl() + "/v2/cards")
                    .header("Authorization", "Bearer " + square.accessToken())
                    .header("Square-Version", SQUARE_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cardBody)
                    .retrieve()
                    .body(JsonNode.class);
            String cardId = cardResponse != null ? cardResponse.path("card").path("id").asText(null) : null;
            if (cardId == null || cardId.isBlank()) {
                return ReusablePaymentMethodSetupResult.failed(
                        "card_create_failed", "Square did not return a card-on-file id.", true);
            }
            return ReusablePaymentMethodSetupResult.succeeded(customerId, cardId, cardResponse.toString());

        } catch (HttpClientErrorException e) {
            String code = parseSquareErrorCode(e.getResponseBodyAsString());
            log.error("Square reusable payment method setup failed: {} — {}", e.getStatusCode(), code);
            return ReusablePaymentMethodSetupResult.failed(code, e.getMessage(), false);
        } catch (Exception e) {
            log.error("Square reusable payment method setup error", e);
            return ReusablePaymentMethodSetupResult.failed("gateway_error", e.getMessage(), true);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> buildSquareAddress(BillingDetails bd) {
        if (bd == null) return null;
        Address addr = bd.address();
        Map<String, Object> map = new HashMap<>();
        if (bd.firstName() != null)        map.put("first_name", bd.firstName());
        if (bd.lastName() != null)         map.put("last_name", bd.lastName());
        if (addr != null) {
            if (addr.line1() != null)      map.put("address_line_1", addr.line1());
            if (addr.line2() != null)      map.put("address_line_2", addr.line2());
            if (addr.city() != null)       map.put("locality", addr.city());
            if (addr.state() != null)      map.put("administrative_district_level_1", addr.state());
            if (addr.postalCode() != null) map.put("postal_code", addr.postalCode());
            if (addr.country() != null)    map.put("country", addr.country());
        }
        return map.isEmpty() ? null : map;
    }

    /** Square requires idempotency_key ≤ 45 chars; derive a deterministic UUID (36 chars). */
    private String squareKey(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String parseSquareErrorCode(String body) {
        try {
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
