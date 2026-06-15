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
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Optional;

/**
 * Receives inbound webhook events from Stripe and reconciles payment intent status.
 * Stripe sends events like payment_intent.succeeded, payment_intent.payment_failed, etc.
 */
@RestController
@RequestMapping("/api/v1/providers/stripe")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final ProcessedWebhookEventRepository processedEventRepository;
    private final DisputeService disputeService;
    private final ObjectMapper objectMapper;

    @Value("${app.stripe.webhook-secret:}")
    private String webhookSecret;

    public StripeWebhookController(PaymentIntentRepository paymentIntentRepository,
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
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Stripe webhook received but webhook secret not configured — rejecting");
            return ResponseEntity.badRequest().build();
        }

        if (sigHeader == null || sigHeader.isBlank()) {
            log.warn("Stripe webhook missing Stripe-Signature header");
            return ResponseEntity.badRequest().build();
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // Idempotency guard — Stripe retries on timeout; ignore events we've already processed
        try {
            processedEventRepository.save(new ProcessedWebhookEvent("STRIPE", event.getId()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok().build(); // already processed
        }

        reconcileFromEvent(event);
        return ResponseEntity.ok().build();
    }

    private void reconcileFromEvent(Event event) {
        try {
            String type = event.getType();
            // Use raw JSON data to avoid Stripe SDK deserialization issues
            JsonNode dataObject = objectMapper.readTree(event.getData().toJson()).path("object");
            reconcileFromJson(type, dataObject);
        } catch (Exception e) {
            log.error("Error processing Stripe event {}: {}", event.getId(), e.getMessage());
        }
    }

    private void reconcileFromJson(String type, JsonNode dataObject) {
        // Dispute events use a different lookup path — route them first
        if (type.startsWith("charge.dispute.")) {
            handleDisputeEvent(type, dataObject);
            return;
        }

        String providerPaymentId = dataObject.path("id").asText(null);
        if (providerPaymentId == null) return;

        Optional<PaymentIntent> intentOpt = paymentIntentRepository
                .findByProviderPaymentId(providerPaymentId);

        if (intentOpt.isEmpty()) {
            log.debug("No PaymentIntent found for Stripe id={}", providerPaymentId);
            return;
        }

        PaymentIntent intent = intentOpt.get();

        switch (type) {
            case "payment_intent.succeeded" -> {
                if (intent.getStatus() != PaymentIntentStatus.SUCCEEDED) {
                    intent.setStatus(PaymentIntentStatus.SUCCEEDED);
                    paymentIntentRepository.save(intent);
                    log.info("Reconciled PaymentIntent {} -> SUCCEEDED via Stripe webhook", intent.getId());
                }
            }
            case "payment_intent.payment_failed" -> {
                if (intent.getStatus() != PaymentIntentStatus.FAILED) {
                    intent.setStatus(PaymentIntentStatus.FAILED);
                    paymentIntentRepository.save(intent);
                    log.info("Reconciled PaymentIntent {} -> FAILED via Stripe webhook", intent.getId());
                }
            }
            case "payment_intent.canceled" -> {
                if (intent.getStatus() != PaymentIntentStatus.CANCELED) {
                    intent.setStatus(PaymentIntentStatus.CANCELED);
                    paymentIntentRepository.save(intent);
                    log.info("Reconciled PaymentIntent {} -> CANCELED via Stripe webhook", intent.getId());
                }
            }
            default -> log.debug("Unhandled Stripe event type: {}", type);
        }
    }

    private void handleDisputeEvent(String type, JsonNode dispute) {
        String providerDisputeId = dispute.path("id").asText(null);
        if (providerDisputeId == null) return;

        // Disputes reference the payment intent via "payment_intent" field
        String providerPaymentId = dispute.path("payment_intent").asText(null);

        DisputeStatus status = switch (type) {
            case "charge.dispute.created" -> mapStripeDisputeStatus(dispute.path("status").asText("needs_response"));
            case "charge.dispute.updated" -> mapStripeDisputeStatus(dispute.path("status").asText("under_review"));
            case "charge.dispute.closed"  -> mapStripeDisputeStatus(dispute.path("status").asText("lost"));
            default -> null;
        };
        if (status == null) return;

        Instant evidenceDueBy = null;
        long dueBySecs = dispute.path("evidence_details").path("due_by").asLong(0);
        if (dueBySecs > 0) evidenceDueBy = Instant.ofEpochSecond(dueBySecs);

        Instant resolvedAt = (status == DisputeStatus.WON || status == DisputeStatus.LOST
                || status == DisputeStatus.CHARGE_REFUNDED) ? Instant.now() : null;

        disputeService.ingestDispute(new DisputeService.IngestDisputeCommand(
                "STRIPE", providerDisputeId, providerPaymentId, status,
                mapStripeDisputeReason(dispute.path("reason").asText("")),
                dispute.path("amount").asLong(0),
                dispute.path("currency").asText("usd").toUpperCase(),
                evidenceDueBy, resolvedAt));
    }

    private DisputeStatus mapStripeDisputeStatus(String s) {
        return switch (s) {
            case "needs_response"         -> DisputeStatus.NEEDS_RESPONSE;
            case "under_review"           -> DisputeStatus.UNDER_REVIEW;
            case "won"                    -> DisputeStatus.WON;
            case "lost"                   -> DisputeStatus.LOST;
            case "charge_refunded"        -> DisputeStatus.CHARGE_REFUNDED;
            case "warning_needs_response" -> DisputeStatus.WARNING_NEEDS_RESPONSE;
            case "warning_under_review"   -> DisputeStatus.WARNING_UNDER_REVIEW;
            case "warning_closed"         -> DisputeStatus.WARNING_CLOSED;
            default                       -> DisputeStatus.NEEDS_RESPONSE;
        };
    }

    private DisputeReason mapStripeDisputeReason(String r) {
        return switch (r) {
            case "fraudulent"            -> DisputeReason.FRAUDULENT;
            case "product_not_received"  -> DisputeReason.PRODUCT_NOT_RECEIVED;
            case "product_unacceptable"  -> DisputeReason.PRODUCT_UNACCEPTABLE;
            case "duplicate"             -> DisputeReason.DUPLICATE;
            case "subscription_canceled" -> DisputeReason.SUBSCRIPTION_CANCELED;
            case "credit_not_processed"  -> DisputeReason.CREDIT_NOT_PROCESSED;
            case "unrecognized"          -> DisputeReason.UNRECOGNIZED;
            default                      -> DisputeReason.GENERAL;
        };
    }
}
