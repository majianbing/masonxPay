package com.masonx.paygateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.masonx.paygateway.domain.payment.BillingDetails;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.PaystackCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class PaystackPaymentProviderService
        extends AbstractPaymentProviderService<PaystackCredentials> {

    private static final Logger log = LoggerFactory.getLogger(PaystackPaymentProviderService.class);

    private final RestClient restClient;

    public PaystackPaymentProviderService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.PAYSTACK;
    }

    @Override
    protected Class<PaystackCredentials> credentialsType() {
        return PaystackCredentials.class;
    }

    @Override
    protected ChargeResult sendCharge(ChargeRequest req, PaystackCredentials paystack) {
        if (paystack.secretKey() == null || paystack.secretKey().isBlank()) {
            return missingConnectorCharge();
        }

        String reference = reference(req);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", req.amount());
            body.put("currency", req.currency().toUpperCase());
            body.put("email", email(req.billingDetails()));
            body.put("reference", reference);
            body.put("callback_url", req.returnUrl() != null ? req.returnUrl() : "https://example.com");
            body.put("channels", java.util.List.of("card"));
            body.put("metadata", Map.of("paymentIntentId", req.paymentIntentId().toString()));

            JsonNode response = restClient.post()
                    .uri(paystack.baseUrl() + "/transaction/initialize")
                    .header("Authorization", "Bearer " + paystack.secretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return new ChargeResult(false, reference, null, "unexpected_response",
                        "Empty response from Paystack", true, false, false, null, null, null);
            }

            boolean status = response.path("status").asBoolean(false);
            String checkoutUrl = response.path("data").path("authorization_url").asText(null);
            String providerReference = response.path("data").path("reference").asText(reference);
            if (!status || checkoutUrl == null || checkoutUrl.isBlank()) {
                String message = response.path("message").asText("Paystack did not return a checkout URL");
                return new ChargeResult(false, reference, response.toString(), "checkout_link_failed",
                        message, false, false, false, null, null, null);
            }
            return ChargeResult.actionRequired(providerReference, response.toString(), "redirect_url", checkoutUrl, null);
        } catch (HttpClientErrorException e) {
            String code = parsePaystackErrorCode(e.getResponseBodyAsString());
            log.error("Paystack charge failed: {} - {}", e.getStatusCode(), code);
            return new ChargeResult(false, reference, e.getResponseBodyAsString(), code,
                    e.getMessage(), false, false, false, null, null, null);
        } catch (Exception e) {
            log.error("Paystack charge error", e);
            return new ChargeResult(false, reference, null, "gateway_error",
                    e.getMessage(), true, false, false, null, null, null);
        }
    }

    @Override
    protected RefundResult sendRefund(RefundRequest req, PaystackCredentials paystack) {
        if (paystack.secretKey() == null || paystack.secretKey().isBlank()) {
            return missingConnectorRefund();
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transaction", req.providerPaymentId());
            body.put("amount", req.amount());
            if (req.reason() != null && !req.reason().isBlank()) {
                body.put("customer_note", req.reason());
            }

            JsonNode response = restClient.post()
                    .uri(paystack.baseUrl() + "/refund")
                    .header("Authorization", "Bearer " + paystack.secretKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) return new RefundResult(false, null, "Empty response from Paystack");
            boolean ok = response.path("status").asBoolean(false);
            String refundId = response.path("data").path("id").asText(null);
            return new RefundResult(ok, refundId, ok ? null : response.path("message").asText("Refund failed"));
        } catch (HttpClientErrorException e) {
            String code = parsePaystackErrorCode(e.getResponseBodyAsString());
            log.error("Paystack refund failed: {} - {}", e.getStatusCode(), code);
            return new RefundResult(false, null, e.getMessage());
        } catch (Exception e) {
            log.error("Paystack refund error", e);
            return new RefundResult(false, null, e.getMessage());
        }
    }

    @Override
    protected Optional<PaymentIntentStatus> sendSyncStatus(String providerPaymentId, PaystackCredentials paystack) {
        if (paystack.secretKey() == null || paystack.secretKey().isBlank()) return Optional.empty();
        try {
            JsonNode response = verifyByReference(providerPaymentId, paystack.secretKey());
            String status = response != null ? response.path("data").path("status").asText("") : "";
            return Optional.ofNullable(mapStatus(status));
        } catch (Exception e) {
            log.warn("Paystack syncStatus failed for {}: {}", providerPaymentId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    protected boolean sendCapture(String providerPaymentId, PaystackCredentials paystack) {
        log.warn("Paystack captureAtProvider not supported for hosted checkout {}", providerPaymentId);
        return false;
    }

    @Override
    protected boolean sendCancel(String providerPaymentId, PaystackCredentials paystack) {
        log.warn("Paystack cancelAtProvider not supported for hosted checkout {}", providerPaymentId);
        return false;
    }

    public JsonNode verifyByReference(String reference, String secretKey) {
        return restClient.get()
                .uri("https://api.paystack.co/transaction/verify/{reference}", reference)
                .header("Authorization", "Bearer " + secretKey)
                .retrieve()
                .body(JsonNode.class);
    }

    public static PaymentIntentStatus mapStatus(String paystackStatus) {
        return switch (paystackStatus == null ? "" : paystackStatus.toLowerCase()) {
            case "success" -> PaymentIntentStatus.SUCCEEDED;
            case "failed", "abandoned", "reversed" -> PaymentIntentStatus.FAILED;
            default -> null;
        };
    }

    private static String reference(ChargeRequest req) {
        String value = req.idempotencyKey() != null && !req.idempotencyKey().isBlank()
                ? req.idempotencyKey()
                : "mxp-" + req.paymentIntentId();
        return value.replaceAll("[^A-Za-z0-9.=-]", "-");
    }

    private static String email(BillingDetails details) {
        return details != null && details.email() != null && !details.email().isBlank()
                ? details.email()
                : "customer@masonxpay.local";
    }

    private static String parsePaystackErrorCode(String body) {
        if (body == null || body.isBlank()) return "paystack_error";
        try {
            int idx = body.indexOf("\"message\":");
            if (idx < 0) return "paystack_error";
            int start = body.indexOf('"', idx + 10) + 1;
            int end = body.indexOf('"', start);
            return body.substring(start, end).toLowerCase().replaceAll("[^a-z0-9]+", "_");
        } catch (Exception ex) {
            return "paystack_error";
        }
    }
}
