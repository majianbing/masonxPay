package com.masonx.paygateway.provider.simulator;

import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * HTTP client that forwards a charge request to rail-service {@code POST /v1/rail/authorize}.
 *
 * <p>Active only when {@code app.rail.enabled=true}. When absent, the in-process
 * Mason Simulator handles the charge (preserving benchmark behaviour).
 *
 * <h3>Outcome mapping</h3>
 * <table>
 *   <tr><th>Rail status</th><th>ChargeResult</th></tr>
 *   <tr><td>APPROVED</td>  <td>success=true, providerPaymentId=railPaymentId</td></tr>
 *   <tr><td>DECLINED</td>  <td>failureCode=simulator_declined</td></tr>
 *   <tr><td>UNKNOWN</td>   <td>failureCode=rail_unknown, providerPaymentId=railPaymentId</td></tr>
 *   <tr><td>FAILED / other</td><td>failureCode=provider_exception</td></tr>
 * </table>
 */
@Component
@ConditionalOnProperty(prefix = "app.rail", name = "enabled", havingValue = "true")
public class RailServiceClient {

    private static final Logger log = LoggerFactory.getLogger(RailServiceClient.class);

    private static final String DEFAULT_TEST_PAN = "4111111111111111"; // last-4 = 1111 → APPROVE
    private static final String DEFAULT_EXPIRY   = "12/25";
    // A valid PAN is 12–19 digits; simulator tokens (e.g. "sim_pm_xxx") are not PANs.
    private static final java.util.regex.Pattern PAN_PATTERN =
            java.util.regex.Pattern.compile("\\d{12,19}");

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public RailServiceClient(@Value("${app.rail.base-url:http://localhost:8081}") String baseUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = baseUrl;
    }

    @SuppressWarnings("unchecked")
    public ChargeResult authorize(ChargeRequest request) {
        // Only use paymentMethodId as testPan if it looks like a numeric PAN.
        // SDK synthetic tokens (e.g. "sim_pm_xxx") are not PANs and would fail
        // the ISO 8583 PAN field encoding in rail-simulator.
        String pmId = request.paymentMethodId();
        String testPan = (pmId != null && PAN_PATTERN.matcher(pmId).matches())
                ? pmId
                : DEFAULT_TEST_PAN;

        // Amount: gateway stores smallest unit (cents); rail-service expects BigDecimal >= 0.01
        BigDecimal amount = BigDecimal.valueOf(request.amount()).movePointLeft(2);

        Map<String, Object> body = Map.of(
                "merchantId",     request.paymentIntentId().toString(),
                "idempotencyKey", request.idempotencyKey(),
                "amount",         amount,
                "currency",       request.currency().toUpperCase(),
                "testPan",        testPan,
                "expiry",         DEFAULT_EXPIRY
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        Map<String, Object> responseBody;
        try {
            responseBody = restTemplate.postForObject(
                    baseUrl + "/v1/rail/authorize", requestEntity, Map.class);
        } catch (HttpClientErrorException e) {
            log.warn("RailServiceClient: 4xx from rail-service for intent={}: {} {}",
                    request.paymentIntentId(), e.getStatusCode(), e.getMessage());
            return declined("simulator_declined", "Rail service rejected request: " + e.getStatusCode());
        } catch (Exception e) {
            log.error("RailServiceClient: connection error for intent={}: {}",
                    request.paymentIntentId(), e.getMessage());
            return exception("provider_exception", "Rail service unreachable: " + e.getMessage());
        }

        if (responseBody == null) {
            return exception("provider_exception", "Rail service returned empty response");
        }

        String railPaymentId = (String) responseBody.get("railPaymentId");
        String status        = (String) responseBody.get("status");

        return switch (status != null ? status : "") {
            case "APPROVED" -> new ChargeResult(
                    true, railPaymentId,
                    "{\"rail\":\"APPROVED\",\"id\":\"" + railPaymentId + "\"}",
                    null, null, false, false, false, null, null, null);
            case "DECLINED" -> declined("simulator_declined",
                    (String) responseBody.getOrDefault("failureReason", "Rail DECLINED"));
            case "UNKNOWN"  -> new ChargeResult(
                    false, railPaymentId,
                    "{\"rail\":\"UNKNOWN\",\"id\":\"" + railPaymentId + "\"}",
                    "rail_unknown", "Rail auth timed out — awaiting reversal resolution",
                    false, true, false, null, null, null);
            default         -> exception("provider_exception",
                    "Rail service returned unexpected status: " + status);
        };
    }

    private ChargeResult declined(String code, String message) {
        return new ChargeResult(false, null, null, code, message, false, false, false, null, null, null);
    }

    private ChargeResult exception(String code, String message) {
        return new ChargeResult(false, null, null, code, message, true, false, false, null, null, null);
    }
}
