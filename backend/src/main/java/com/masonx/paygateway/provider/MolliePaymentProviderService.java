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
 * Charge flow (hosted redirect):
 *   1. POST /v2/payments → get checkout URL from _links.checkout.href
 *   2. Return ChargeResult.actionRequired("redirect_url", checkoutUrl)
 *   3. Browser SDK opens Mollie's hosted checkout in an iframe overlay
 *   4. Mollie redirects back to our /pay/3ds-return page → overlay closes → status polled
 *   5. Mollie webhook reconciles the final status asynchronously
 *
 * Amount format: Mollie requires decimal strings ("10.00"), not cents integers.
 * Convert: amount (cents) → String.format("%.2f", amount / 100.0)
 *
 * Sandbox vs. production: determined by the API key prefix (test_ vs live_).
 * Both use the same API endpoint — https://api.mollie.com/v2.
 */
@Service
public class MolliePaymentProviderService implements PaymentProviderService, ReusablePaymentMethodProviderService {

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
    public ChargeResult charge(ChargeRequest req, ProviderCredentials creds) {
        if (!(creds instanceof MollieCredentials mollie)) {
            return new ChargeResult(false, null, null,
                    "connector_not_configured", "No active Mollie connector found.",
                    false, false, null, null, null);
        }

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

            // Optional: set webhookUrl per-payment if configured globally
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
                        "Empty response from Mollie", true, false, null, null, null);
            }

            String molliePaymentId  = response.path("id").asText(null);
            String status           = response.path("status").asText("");
            String checkoutUrl      = response.path("_links").path("checkout").path("href").asText(null);
            String responseJson     = response.toString();

            if (checkoutUrl == null || checkoutUrl.isBlank()) {
                // Payment may have failed immediately (e.g. method not allowed in sandbox)
                return new ChargeResult(false, molliePaymentId, responseJson,
                        "no_checkout_url", "Mollie did not return a checkout URL (status=" + status + ")",
                        false, false, null, null, null);
            }

            // Mollie is always a redirect flow — return redirect_url action for the browser SDK
            return ChargeResult.actionRequired(molliePaymentId, responseJson, "redirect_url", checkoutUrl, null);

        } catch (HttpClientErrorException e) {
            String code = parseMollieErrorCode(e.getResponseBodyAsString());
            log.error("Mollie charge failed: {} — {}", e.getStatusCode(), code);
            return new ChargeResult(false, null, null, code, e.getMessage(),
                    false, false, null, null, null);
        } catch (Exception e) {
            log.error("Mollie charge error", e);
            return new ChargeResult(false, null, null, "gateway_error", e.getMessage(),
                    true, false, null, null, null);
        }
    }

    @Override
    public ReusablePaymentMethodSetupResult setupReusablePaymentMethod(
            ReusablePaymentMethodSetupRequest request,
            ProviderCredentials creds) {
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
                    if (request.billingDetails().email() != null) {
                        customerBody.put("email", request.billingDetails().email());
                    }
                    String name = fullName(request.billingDetails().firstName(), request.billingDetails().lastName());
                    if (!name.isBlank()) {
                        customerBody.put("name", name);
                    }
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
            return ReusablePaymentMethodSetupResult.actionRequired(
                    customerId,
                    "{\"provider\":\"MOLLIE\",\"customerId\":\"" + customerId + "\"}",
                    "mollie_first_payment",
                    null,
                    null);
        } catch (HttpClientErrorException e) {
            String code = parseMollieErrorCode(e.getResponseBodyAsString());
            log.error("Mollie reusable payment method setup failed: {} — {}", e.getStatusCode(), code);
            return ReusablePaymentMethodSetupResult.failed(code, e.getMessage(), false);
        } catch (Exception e) {
            log.error("Mollie reusable payment method setup error", e);
            return ReusablePaymentMethodSetupResult.failed("gateway_error", e.getMessage(), true);
        }
    }

    @Override
    public RefundResult refund(RefundRequest req, ProviderCredentials creds) {
        if (!(creds instanceof MollieCredentials mollie)) {
            return new RefundResult(false, null, "No active Mollie connector found.");
        }

        try {
            // RefundRequest does not carry currency; Mollie refunds inherit it from the original payment.
            // We still must pass it in the request — use EUR as the default (Mollie validates against original).
            Map<String, Object> body = Map.of(
                    "amount", Map.of(
                            "currency", "EUR",
                            "value",    formatAmount(req.amount())));

            JsonNode response = restClient.post()
                    .uri(mollie.baseUrl() + "/payments/" + req.providerPaymentId() + "/refunds")
                    .header("Authorization", "Bearer " + mollie.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            if (response == null) {
                return new RefundResult(false, null, "Empty response from Mollie");
            }

            String refundId = response.path("id").asText(null);
            String status   = response.path("status").asText("");
            // queued / pending / processing / refunded — all non-failure states
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
    public Optional<PaymentIntentStatus> syncStatus(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof MollieCredentials mollie)) return Optional.empty();
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
    public boolean cancelAtProvider(String providerPaymentId, ProviderCredentials creds) {
        if (!(creds instanceof MollieCredentials mollie)) return false;
        try {
            // Mollie cancel: DELETE /v2/payments/{id} — only works when status = "open"
            restClient.delete()
                    .uri(mollie.baseUrl() + "/payments/" + providerPaymentId)
                    .header("Authorization", "Bearer " + mollie.apiKey())
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            // 422 = payment cannot be canceled (already paid/expired)
            log.warn("Mollie cancelAtProvider failed for {}: {}", providerPaymentId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Mollie cancelAtProvider error for {}: {}", providerPaymentId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean captureAtProvider(String providerPaymentId, ProviderCredentials creds) {
        // Mollie supports manual capture only for certain payment methods via separate captures API.
        // Not implemented — AUTOMATIC capture used by default.
        log.warn("Mollie captureAtProvider not implemented for {}", providerPaymentId);
        return false;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Fetches a Mollie payment and returns the full JSON node.
     * Used by the webhook controller to verify the payment exists and get its status.
     */
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

    /** Maps Mollie payment status strings to local PaymentIntentStatus. */
    public static PaymentIntentStatus mapStatus(String mollieStatus) {
        return switch (mollieStatus.toLowerCase()) {
            case "paid"        -> PaymentIntentStatus.SUCCEEDED;
            case "failed"      -> PaymentIntentStatus.FAILED;
            case "expired"     -> PaymentIntentStatus.FAILED;
            case "canceled"    -> PaymentIntentStatus.CANCELED;
            case "authorized"  -> PaymentIntentStatus.REQUIRES_CAPTURE;
            default            -> null; // open / pending / in-flight
        };
    }

    /** Formats a cents amount as a Mollie-compatible decimal string ("10.00"). */
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
