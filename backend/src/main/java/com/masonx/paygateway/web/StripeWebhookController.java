package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
    private final ObjectMapper objectMapper;

    @Value("${app.stripe.webhook-secret:}")
    private String webhookSecret;

    public StripeWebhookController(PaymentIntentRepository paymentIntentRepository,
                                    ObjectMapper objectMapper) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader) {

        Event event;
        if (webhookSecret != null && !webhookSecret.isBlank() && sigHeader != null) {
            try {
                event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
            } catch (SignatureVerificationException e) {
                log.warn("Stripe webhook signature verification failed: {}", e.getMessage());
                return ResponseEntity.badRequest().build();
            }
        } else {
            // No secret configured — parse without verification (dev/test only)
            try {
                JsonNode root = objectMapper.readTree(payload);
                // Minimal parse for dev mode
                String type = root.path("type").asText();
                JsonNode dataObject = root.path("data").path("object");
                reconcileFromJson(type, dataObject);
                return ResponseEntity.ok().build();
            } catch (Exception e) {
                log.error("Failed to parse Stripe webhook: {}", e.getMessage());
                return ResponseEntity.badRequest().build();
            }
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
}
