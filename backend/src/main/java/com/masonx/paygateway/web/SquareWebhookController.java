package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Receives inbound webhook notifications from Square and reconciles payment intent status.
 *
 * Square signs webhooks using HMAC-SHA256:
 *   signature = Base64( HMAC-SHA256( signatureKey, notificationUrl + rawBody ) )
 * The result is sent in the "x-square-hmacsha256-signature" header.
 *
 * Required configuration:
 *   app.square.webhook-signature-key   — from Square Developer Dashboard → Webhooks → signature key
 *   app.square.webhook-notification-url — the full URL Square posts to (e.g. https://pay.example.com/api/v1/providers/square/webhook)
 *
 * If either property is blank the endpoint rejects all requests with 400.
 *
 * Webhook endpoint to register in Square Developer Dashboard:
 *   POST /api/v1/providers/square/webhook
 *
 * Subscribed event types:
 *   - payment.completed  → SUCCEEDED
 *   - payment.failed     → FAILED
 *   - payment.canceled   → CANCELED
 */
@RestController
@RequestMapping("/api/v1/providers/square")
public class SquareWebhookController {

    private static final Logger log = LoggerFactory.getLogger(SquareWebhookController.class);
    private static final String SIGNATURE_HEADER = "x-square-hmacsha256-signature";

    private final PaymentIntentRepository paymentIntentRepository;
    private final ObjectMapper objectMapper;

    @Value("${app.square.webhook-signature-key:}")
    private String signatureKey;

    @Value("${app.square.webhook-notification-url:}")
    private String notificationUrl;

    public SquareWebhookController(PaymentIntentRepository paymentIntentRepository,
                                   ObjectMapper objectMapper) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {

        if (signatureKey == null || signatureKey.isBlank()
                || notificationUrl == null || notificationUrl.isBlank()) {
            log.warn("Square webhook received but signature key or notification URL not configured — rejecting");
            return ResponseEntity.badRequest().build();
        }

        if (signature == null || signature.isBlank()) {
            log.warn("Square webhook missing {} header", SIGNATURE_HEADER);
            return ResponseEntity.badRequest().build();
        }

        if (!isSignatureValid(signature, payload)) {
            log.warn("Square webhook signature verification failed");
            return ResponseEntity.badRequest().build();
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = root.path("type").asText(null);
            JsonNode paymentNode = root.path("data").path("object").path("payment");
            reconcile(type, paymentNode);
        } catch (Exception e) {
            log.error("Failed to parse Square webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok().build();
    }

    private boolean isSignatureValid(String signature, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signatureKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((notificationUrl + payload).getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getEncoder().encodeToString(hash);
            // Constant-time comparison to prevent timing attacks
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Square webhook signature computation failed: {}", e.getMessage());
            return false;
        }
    }

    private void reconcile(String type, JsonNode paymentNode) {
        if (type == null || paymentNode == null || paymentNode.isMissingNode()) {
            log.debug("Square webhook: missing type or payment object, skipping");
            return;
        }

        PaymentIntentStatus newStatus = switch (type) {
            case "payment.completed" -> PaymentIntentStatus.SUCCEEDED;
            case "payment.failed"    -> PaymentIntentStatus.FAILED;
            case "payment.canceled"  -> PaymentIntentStatus.CANCELED;
            default -> null;
        };

        if (newStatus == null) {
            log.debug("Unhandled Square webhook type: {}", type);
            return;
        }

        String providerPaymentId = paymentNode.path("id").asText(null);
        if (providerPaymentId == null) {
            log.debug("Square webhook {}: no payment id, skipping", type);
            return;
        }

        Optional<PaymentIntent> intentOpt = paymentIntentRepository
                .findByProviderPaymentId(providerPaymentId);

        if (intentOpt.isEmpty()) {
            log.debug("No PaymentIntent found for Square payment id={}", providerPaymentId);
            return;
        }

        PaymentIntent intent = intentOpt.get();
        if (intent.getStatus() != newStatus) {
            intent.setStatus(newStatus);
            paymentIntentRepository.save(intent);
            log.info("Reconciled PaymentIntent {} -> {} via Square webhook ({})",
                    intent.getId(), newStatus, type);
        }
    }
}
