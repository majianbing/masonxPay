package com.masonx.paygateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.MollieCredentials;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mollie payment provider — REST API v2 via RestClient. No SDK dependency.
 *
 * Charge flow (hosted redirect): POST /v2/payments → checkout URL →
 * ChargeResult.actionRequired("redirect_url", checkoutUrl) → browser SDK
 * opens Mollie's hosted checkout in an iframe → webhook reconciles status.
 *
 * Amount format: Mollie requires decimal strings ("10.00"), not cents integers.
 * Sandbox vs. production: determined by the API key prefix (test_ vs live_).
 */
@Service
public class MolliePaymentProviderService
        extends AbstractPaymentProviderService<MollieCredentials>
        implements ReusablePaymentMethodProviderService {

    private static final Logger log = LoggerFactory.getLogger(MolliePaymentProviderService.class);

    private final RestClient restClient;

    @Value("${app.mollie.webhook-url:}")
    private String webhookUrl;

    public MolliePaymentProviderService(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public PaymentProvider brand() {
        return PaymentProvider.MOLLIE;
    }

    @Override
    protected Class<MollieCredentials> credentialsType() {
        return MollieCredentials.class;
    }

    @Override
    protected ChargeResult sendCharge(ChargeRequest req, MollieCredentials mollie) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("amount", Map.of(
                    "currency", req.currency().toUpperCase(),
                    "value",    formatAmount(req.amount())));
            body.put("description", buildDescription(req));
            body.put("redirectUrl", req.returnUrl() != null ? req.returnUrl() : "https://example.com");
            body.put("metadata", Map.of("paymentIntentId", req.paymentIntentId().toString()));
            if (req.providerCustomerReference() != null && !req.providerCustomerReference().isBlank()) {
                body.put("customerId", req.providerCustomerReference());
                body.put("sequenceType", "first");
            }
            if (webhookUrl != null && !webhookUrl.isBlank()) {
                body.put("webhookUrl", webhookUrl);
            }

            JsonNode response = restClient.post()
                    .uri(mollie.baseUrl() + "/payments")
                    .header("Authorization", "Bearer " + mollie.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return new ChargeResult(false, null, null, "unexpected_response",
                        "Empty response from Mollie", true, false, false, null, null, null);
            }

            String molliePaymentId = response.path("id").asText(null);
            String status          = response.path("status").asText("");
            String checkoutUrl     = response.path("_links").path("checkout").path("href").asText(null);
            String responseJson    = response.toString();

            if (checkoutUrl == null || checkoutUrl.isBlank()) {
                return new ChargeResult(false, molliePaymentId, responseJson, "no_checkout_url",
                        "Mollie did not return a checkout URL (status=" + status + ")",
                        false, false, false, null, null, null);
            }
            return ChargeResult.actionRequired(molliePaymentId, responseJson, "redirect_url", checkoutUrl, null);

        } catch (HttpClientErrorException e) {
            String code = parseMollieErrorCode(e.getResponseBodyAsString());
            log.error("Mollie charge failed: {} — {}", e.getStatusCode(), code);
            return new ChargeResult(false, null, null, code, e.getMessage(), false, false, false, null, null, null);
        } catch (Exception e) {
            log.error("Mollie charge error", e);
            return new ChargeResult(false, null, null, "gateway_error", e.getMessage(), true, false, false, null, null, null);
        }
    }

    @Override
    protected RefundResult sendRefund(RefundRequest req, MollieCredentials mollie) {
        try {
            Map<String, Object> body = Map.of(
                    "amount", Map.of("currency", "EUR", "value", formatAmount(req.amount())));

            JsonNode response = restClient.post()
                    .uri(mollie.baseUrl() + "/payments/" + req.providerPaymentId() + "/refunds")
                    .header("Authorization", "Bearer " + mollie.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) return new RefundResult(false, null, "Empty response from Mollie");
            String refundId = response.path("id").asText(null);
            String status   = response.path("status").asText("");
            boolean ok = !"failed".equalsIgnoreCase(status);
            return new RefundResult(ok, refundId, ok ? null : "Refund status: " + status);

        } catch (HttpClientErrorException e) {
            String code = parseMollieErrorCode(e.getResponseBodyAsString());
            log.error("Mollie refund failed: {} — {}", e.getStatusCode(), code);
            return new RefundResult(false, null, e.getMessage());
        } catch (Exception e) {
            log.error("Mollie refund error", e);
            return new RefundResult(false, null, e.getMessage());
        }
    }

    @Override
    protected Optional<PaymentIntentStatus> sendSyncStatus(String providerPaymentId, MollieCredentials mollie) {
        try {
            JsonNode response = restClient.get()
                    .uri(mollie.baseUrl() + "/payments/" + providerPaymentId)
                    .header("Authorization", "Bearer " + mollie.apiKey())
                    .retrieve()
                    .body(JsonNode.class);
            String status = response != null ? response.path("status").asText("") : "";
            return Optional.ofNullable(mapStatus(status));
        } catch (Exception e) {
            log.warn("Mollie syncStatus failed for {}: {}", providerPaymentId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    protected boolean sendCapture(String providerPaymentId, MollieCredentials mollie) {
        log.warn("Mollie captureAtProvider not implemented for {}", providerPaymentId);
        return false;
    }

    @Override
    protected boolean sendCancel(String providerPaymentId, MollieCredentials mollie) {
        try {
            restClient.delete()
                    .uri(mollie.baseUrl() + "/payments/" + providerPaymentId)
                    .header("Authorization", "Bearer " + mollie.apiKey())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            log.warn("Mollie cancelAtProvider failed for {}: {}", providerPaymentId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Mollie cancelAtProvider error for {}: {}", providerPaymentId, e.getMessage());
            return false;
        }
    }

    // ── ReusablePaymentMethodProviderService ──────────────────────────────────

    @Override
    public ReusablePaymentMethodSetupResult setupReusablePaymentMethod(
            ReusablePaymentMethodSetupRequest request, ProviderCredentials creds) {
        if (!(creds instanceof MollieCredentials mollie)) {
            return ReusablePaymentMethodSetupResult.failed(
                    "connector_not_configured", "No active Mollie connector found.", false);
        }

        try {
            String customerId = request.existingProviderCustomerReference();
            if (customerId == null || customerId.isBlank()) {
                Map<String, Object> customerBody = new LinkedHashMap<>();
                customerBody.put("metadata", Map.of("masonxpayCustomerId", request.customerId().toString()));
                if (request.billingDetails() != null) {
                    if (request.billingDetails().email() != null)
                        customerBody.put("email", request.billingDetails().email());
                    String name = fullName(request.billingDetails().firstName(), request.billingDetails().lastName());
                    if (!name.isBlank()) customerBody.put("name", name);
                }
                JsonNode response = restClient.post()
                        .uri(mollie.baseUrl() + "/customers")
                        .header("Authorization", "Bearer " + mollie.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(customerBody)
                        .retrieve()
                        .body(JsonNode.class);
                customerId = response != null ? response.path("id").asText(null) : null;
                if (customerId == null || customerId.isBlank()) {
                    return ReusablePaymentMethodSetupResult.failed(
                            "customer_create_failed", "Mollie did not return a customer id.", true);
                }
                return ReusablePaymentMethodSetupResult.actionRequired(
                        customerId, response.toString(), "mollie_first_payment", null, null);
            }
            return ReusablePaymentMethodSetupResult.actionRequired(customerId,
                    "{\"provider\":\"MOLLIE\",\"customerId\":\"" + customerId + "\"}",
                    "mollie_first_payment", null, null);
        } catch (HttpClientErrorException e) {
            String code = parseMollieErrorCode(e.getResponseBodyAsString());
            log.error("Mollie reusable payment method setup failed: {} — {}", e.getStatusCode(), code);
            return ReusablePaymentMethodSetupResult.failed(code, e.getMessage(), false);
        } catch (Exception e) {
            log.error("Mollie reusable payment method setup error", e);
            return ReusablePaymentMethodSetupResult.failed("gateway_error", e.getMessage(), true);
        }
    }

    // ── Public helpers used by MollieWebhookController ────────────────────────

    public JsonNode fetchPayment(String molliePaymentId, String apiKey) {
        try {
            return restClient.get()
                    .uri("https://api.mollie.com/v2/payments/" + molliePaymentId)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("Mollie fetchPayment failed for {}: {}", molliePaymentId, e.getMessage());
            return null;
        }
    }

    public static PaymentIntentStatus mapStatus(String mollieStatus) {
        return switch (mollieStatus.toLowerCase()) {
            case "paid"       -> PaymentIntentStatus.SUCCEEDED;
            case "failed"     -> PaymentIntentStatus.FAILED;
            case "expired"    -> PaymentIntentStatus.FAILED;
            case "canceled"   -> PaymentIntentStatus.CANCELED;
            case "authorized" -> PaymentIntentStatus.REQUIRES_CAPTURE;
            default           -> null;
        };
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String formatAmount(long cents) {
        return String.format("%.2f", cents / 100.0);
    }

    private static String buildDescription(ChargeRequest req) {
        if (req.billingDetails() != null && req.billingDetails().email() != null) {
            return "Payment for " + req.billingDetails().email();
        }
        return "Payment " + req.paymentIntentId();
    }

    private static String parseMollieErrorCode(String body) {
        try {
            int idx = body.indexOf("\"title\":");
            if (idx < 0) return "mollie_error";
            int s = body.indexOf('"', idx + 8) + 1;
            int e = body.indexOf('"', s);
            return body.substring(s, e).toLowerCase().replace(' ', '_');
        } catch (Exception ex) {
            return "mollie_error";
        }
    }

    private static String fullName(String first, String last) {
        if (first == null && last == null) return "";
        if (first == null) return last;
        if (last == null) return first;
        return first + " " + last;
    }
}
