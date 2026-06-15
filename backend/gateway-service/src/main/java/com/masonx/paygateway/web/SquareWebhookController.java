package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.dispute.DisputeReason;
import com.masonx.paygateway.domain.dispute.DisputeStatus;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.webhook.ProcessedWebhookEvent;
import com.masonx.paygateway.domain.webhook.ProcessedWebhookEventRepository;
import com.masonx.paygateway.service.DisputeService;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
    private final ProcessedWebhookEventRepository processedEventRepository;
    private final DisputeService disputeService;
    private final ObjectMapper objectMapper;

    @Value("${app.square.webhook-signature-key:}")
    private String signatureKey;

    @Value("${app.square.webhook-notification-url:}")
    private String notificationUrl;

    public SquareWebhookController(PaymentIntentRepository paymentIntentRepository,
                                   ProcessedWebhookEventRepository processedEventRepository,
                                   DisputeService disputeService,
                                   ObjectMapper objectMapper) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.processedEventRepository = processedEventRepository;
        this.disputeService = disputeService;
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
            String eventId = root.path("event_id").asText(null);
            String type = root.path("type").asText(null);
            JsonNode paymentNode = root.path("data").path("object").path("payment");

            // Idempotency guard — Square retries on timeout
            if (eventId != null) {
                try {
                    processedEventRepository.save(new ProcessedWebhookEvent("SQUARE", eventId));
                } catch (DataIntegrityViolationException e) {
                    return ResponseEntity.ok().build(); // already processed
                }
            }

            if (type != null && type.startsWith("dispute.")) {
                handleDisputeEvent(type, root.path("data").path("object").path("dispute"));
            } else {
                reconcile(type, paymentNode);
            }
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok().build();
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

    private void handleDisputeEvent(String type, JsonNode dispute) {
        String providerDisputeId = dispute.path("id").asText(null);
        if (providerDisputeId == null) return;

        String providerPaymentId = dispute.path("payment_id").asText(null);
        String state = dispute.path("state").asText("");

        DisputeStatus status = switch (state) {
            case "EVIDENCE_REQUIRED",
                 "INQUIRY_EVIDENCE_REQUIRED" -> DisputeStatus.NEEDS_RESPONSE;
            case "PROCESSING",
                 "INQUIRY_PROCESSING"        -> DisputeStatus.UNDER_REVIEW;
            case "WON"                       -> DisputeStatus.WON;
            case "LOST"                      -> DisputeStatus.LOST;
            case "ACCEPTED"                  -> DisputeStatus.CHARGE_REFUNDED;
            default                          -> DisputeStatus.NEEDS_RESPONSE;
        };

        DisputeReason reason = mapSquareDisputeReason(dispute.path("reason").asText(""));

        long amountCents = 0;
        JsonNode amountMoney = dispute.path("amount_money");
        if (!amountMoney.isMissingNode()) {
            amountCents = amountMoney.path("amount").asLong(0);
        }
        String currency = amountMoney.path("currency").asText("USD");

        Instant evidenceDueBy = null;
        String dueAt = dispute.path("evidence_due_at").asText(null);
        if (dueAt != null && !dueAt.isBlank()) {
            try { evidenceDueBy = Instant.parse(dueAt); } catch (Exception ignored) {}
        }

        Instant resolvedAt = (status == DisputeStatus.WON || status == DisputeStatus.LOST
                || status == DisputeStatus.CHARGE_REFUNDED) ? Instant.now() : null;

        disputeService.ingestDispute(new com.masonx.paygateway.service.DisputeService.IngestDisputeCommand(
                "SQUARE", providerDisputeId, providerPaymentId, status, reason,
                amountCents, currency, evidenceDueBy, resolvedAt));
    }

    private DisputeReason mapSquareDisputeReason(String r) {
        return switch (r) {
            case "DUPLICATE"                -> DisputeReason.DUPLICATE;
            case "NOT_RECEIVED"             -> DisputeReason.PRODUCT_NOT_RECEIVED;
            case "NOT_AS_DESCRIBED"         -> DisputeReason.PRODUCT_UNACCEPTABLE;
            case "CANCELLED"                -> DisputeReason.SUBSCRIPTION_CANCELED;
            case "CREDIT_NOT_PROCESSED",
                 "CUSTOMER_REQUESTS_CREDIT" -> DisputeReason.CREDIT_NOT_PROCESSED;
            case "NO_KNOWLEDGE",
                 "EMV_LIABILITY_SHIFT"      -> DisputeReason.FRAUDULENT;
            default                         -> DisputeReason.GENERAL;
        };
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
