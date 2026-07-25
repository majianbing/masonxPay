package com.masonx.paygateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.FlutterwaveCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class FlutterwavePaymentProviderService
        extends AbstractPaymentProviderService<FlutterwaveCredentials> {

    private static final Logger log = LoggerFactory.getLogger(FlutterwavePaymentProviderService.class);

    private final RestClient restClient;

    public FlutterwavePaymentProviderService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.FLUTTERWAVE;
    }

    @Override
    protected Class<FlutterwaveCredentials> credentialsType() {
        return FlutterwaveCredentials.class;
    }

    @Override
    protected ChargeResult sendCharge(ChargeRequest req, FlutterwaveCredentials flutterwave) {
        if (flutterwave.secretKey() == null || flutterwave.secretKey().isBlank()) {
            return missingConnectorCharge();
        }

        String txRef = txRef(req);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tx_ref", txRef);
            body.put("amount", formatAmount(req.amount()));
            body.put("currency", req.currency().toUpperCase());
            body.put("redirect_url", req.returnUrl() != null ? req.returnUrl() : "https://example.com");
            body.put("payment_options", req.paymentMethodType() != null ? req.paymentMethodType() : "card");
            body.put("customer", customer(req.billingDetails()));
            body.put("customizations", Map.of(
                    "title", "MasonXPay checkout",
                    "description", "Payment " + req.paymentIntentId()
            ));
            body.put("meta", Map.of("paymentIntentId", req.paymentIntentId().toString()));

            JsonNode response = restClient.post()
                    .uri(flutterwave.baseUrl() + "/payments")
                    .header("Authorization", "Bearer " + flutterwave.secretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return new ChargeResult(false, txRef, null, "unexpected_response",
                        "Empty response from Flutterwave", true, false, false, null, null, null);
            }

            String status = response.path("status").asText("");
            String checkoutUrl = response.path("data").path("link").asText(null);
            if (!"success".equalsIgnoreCase(status) || checkoutUrl == null || checkoutUrl.isBlank()) {
                String message = response.path("message").asText("Flutterwave did not return a checkout URL");
                return new ChargeResult(false, txRef, response.toString(), "checkout_link_failed",
                        message, false, false, false, null, null, null);
            }
            return ChargeResult.actionRequired(txRef, response.toString(), "redirect_url", checkoutUrl, null);
        } catch (HttpClientErrorException e) {
            String code = parseFlutterwaveErrorCode(e.getResponseBodyAsString());
            log.error("Flutterwave charge failed: {} - {}", e.getStatusCode(), code);
            return new ChargeResult(false, txRef, e.getResponseBodyAsString(), code,
                    e.getMessage(), false, false, false, null, null, null);
        } catch (Exception e) {
            log.error("Flutterwave charge error", e);
            return new ChargeResult(false, txRef, null, "gateway_error",
                    e.getMessage(), true, false, false, null, null, null);
        }
    }

    @Override
    protected RefundResult sendRefund(RefundRequest req, FlutterwaveCredentials flutterwave) {
        if (flutterwave.secretKey() == null || flutterwave.secretKey().isBlank()) {
            return missingConnectorRefund();
        }

        try {
            JsonNode transaction = verifyByReference(req.providerPaymentId(), flutterwave.secretKey());
            String transactionId = transaction != null ? transaction.path("data").path("id").asText(null) : null;
            if (transactionId == null || transactionId.isBlank()) {
                return new RefundResult(false, null, "Flutterwave transaction could not be verified before refund");
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", formatAmount(req.amount()));
            if (req.reason() != null && !req.reason().isBlank()) {
                body.put("comments", req.reason());
            }

            JsonNode response = restClient.post()
                    .uri(flutterwave.baseUrl() + "/transactions/" + transactionId + "/refund")
                    .header("Authorization", "Bearer " + flutterwave.secretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) return new RefundResult(false, null, "Empty response from Flutterwave");
            String status = response.path("status").asText("");
            String refundStatus = response.path("data").path("status").asText("");
            boolean ok = "success".equalsIgnoreCase(status) && !"failed".equalsIgnoreCase(refundStatus);
            String refundId = response.path("data").path("id").asText(null);
            return new RefundResult(ok, refundId, ok ? null : response.path("message").asText("Refund failed"));
        } catch (HttpClientErrorException e) {
            String code = parseFlutterwaveErrorCode(e.getResponseBodyAsString());
            log.error("Flutterwave refund failed: {} - {}", e.getStatusCode(), code);
            return new RefundResult(false, null, e.getMessage());
        } catch (Exception e) {
            log.error("Flutterwave refund error", e);
            return new RefundResult(false, null, e.getMessage());
        }
    }

    @Override
    protected Optional<PaymentIntentStatus> sendSyncStatus(String providerPaymentId, FlutterwaveCredentials flutterwave) {
        if (flutterwave.secretKey() == null || flutterwave.secretKey().isBlank()) return Optional.empty();
        try {
            JsonNode response = verifyByReference(providerPaymentId, flutterwave.secretKey());
            String status = response != null ? response.path("data").path("status").asText("") : "";
            return Optional.ofNullable(mapStatus(status));
        } catch (Exception e) {
            log.warn("Flutterwave syncStatus failed for {}: {}", providerPaymentId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    protected boolean sendCapture(String providerPaymentId, FlutterwaveCredentials flutterwave) {
        log.warn("Flutterwave captureAtProvider not supported for hosted checkout {}", providerPaymentId);
        return false;
    }

    @Override
    protected boolean sendCancel(String providerPaymentId, FlutterwaveCredentials flutterwave) {
        log.warn("Flutterwave cancelAtProvider not supported for hosted checkout {}", providerPaymentId);
        return false;
    }

    public JsonNode verifyByReference(String txRef, String secretKey) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("api.flutterwave.com")
                        .path("/v3/transactions/verify_by_reference")
                        .queryParam("tx_ref", txRef)
                        .build())
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .body(JsonNode.class);
    }

    public JsonNode verifyById(String transactionId, String secretKey) {
        return restClient.get()
                .uri("https://api.flutterwave.com/v3/transactions/" + transactionId + "/verify")
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .body(JsonNode.class);
    }

    public static PaymentIntentStatus mapStatus(String flutterwaveStatus) {
        return switch (flutterwaveStatus == null ? "" : flutterwaveStatus.toLowerCase()) {
            case "successful" -> PaymentIntentStatus.SUCCEEDED;
            case "failed" -> PaymentIntentStatus.FAILED;
            case "cancelled", "canceled" -> PaymentIntentStatus.CANCELED;
            default -> null;
        };
    }

    private static String txRef(ChargeRequest req) {
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) return req.idempotencyKey();
        return "mxp-" + req.paymentIntentId();
    }

    private static Map<String, Object> customer(BillingDetails details) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("email", details != null && details.email() != null ? details.email() : "customer@masonxpay.local");
        String name = fullName(details);
        if (!name.isBlank()) customer.put("name", name);
        if (details != null && details.phone() != null && !details.phone().isBlank()) {
            customer.put("phonenumber", details.phone());
        }
        return customer;
    }

    private static String fullName(BillingDetails details) {
        if (details == null) return "";
        String first = details.firstName() == null ? "" : details.firstName();
        String last = details.lastName() == null ? "" : details.lastName();
        return (first + " " + last).trim();
    }

    private static String formatAmount(long minorUnits) {
        return BigDecimal.valueOf(minorUnits)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.UNNECESSARY)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String parseFlutterwaveErrorCode(String body) {
        if (body == null || body.isBlank()) return "flutterwave_error";
        try {
            int idx = body.indexOf("\"message\":");
            if (idx < 0) return "flutterwave_error";
            int start = body.indexOf('"', idx + 10) + 1;
            int end = body.indexOf('"', start);
            return body.substring(start, end).toLowerCase().replaceAll("[^a-z0-9]+", "_");
        } catch (Exception ex) {
            return "flutterwave_error";
        }
    }
}
