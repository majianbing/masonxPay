package com.masonx.paygateway.web;

import com.braintreegateway.BraintreeGateway;
import com.braintreegateway.Environment;
import com.braintreegateway.WebhookNotification;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Receives inbound webhook notifications from Braintree and reconciles payment intent status.
 *
 * Braintree sends webhooks as HTTP POST with form-encoded bt_signature + bt_payload.
 * bt_payload is a base64-encoded XML document.
 *
 * Signature verification requires a BraintreeGateway instance (merchantId + publicKey +
 * privateKey). Configure via app.braintree.* properties. If credentials are not configured
 * the endpoint rejects all requests with 400.
 *
 * Webhook endpoint to register in Braintree Control Panel:
 *   POST /api/v1/providers/braintree/webhook
 *
 * Subscribed event kinds:
 *   - transaction_settled         → SUCCEEDED
 *   - transaction_settlement_declined → FAILED
 */
@RestController
@RequestMapping("/api/v1/providers/braintree")
public class BraintreeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BraintreeWebhookController.class);

    private final PaymentIntentRepository paymentIntentRepository;

    @Value("${app.braintree.merchant-id:}")
    private String merchantId;
    @Value("${app.braintree.public-key:}")
    private String publicKey;
    @Value("${app.braintree.private-key:}")
    private String privateKey;
    @Value("${app.braintree.sandbox:true}")
    private boolean sandbox;

    public BraintreeWebhookController(PaymentIntentRepository paymentIntentRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestParam(value = "bt_signature", required = false) String btSignature,
            @RequestParam(value = "bt_payload",   required = false) String btPayload) {

        if (!isVerificationConfigured()) {
            log.warn("Braintree webhook received but gateway credentials not configured — rejecting");
            return ResponseEntity.badRequest().build();
        }

        if (btPayload == null) {
            log.warn("Braintree webhook received with no bt_payload");
            return ResponseEntity.badRequest().build();
        }

        if (btSignature == null || btSignature.isBlank()) {
            log.warn("Braintree webhook missing bt_signature");
            return ResponseEntity.badRequest().build();
        }

        try {
            BraintreeGateway gateway = buildGateway();
            WebhookNotification notification = gateway.webhookNotification()
                    .parse(btSignature, btPayload);
            reconcileFromNotification(notification);
        } catch (Exception e) {
            log.warn("Braintree webhook verification/parse failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok().build();
    }

    private void reconcileFromNotification(WebhookNotification notification) {
        String kind = notification.getKind() != null ? notification.getKind().toString() : "";

        if ("CHECK".equals(kind)) {
            log.debug("Braintree webhook check received");
            return;
        }

        if (notification.getTransaction() == null) {
            log.debug("Braintree webhook {} has no transaction; skipping", kind);
            return;
        }

        String providerPaymentId = notification.getTransaction().getId();
        PaymentIntentStatus newStatus = switch (kind) {
            case "TRANSACTION_SETTLED"              -> PaymentIntentStatus.SUCCEEDED;
            case "TRANSACTION_SETTLEMENT_DECLINED"  -> PaymentIntentStatus.FAILED;
            default -> null;
        };

        if (newStatus != null) {
            updateIntent(providerPaymentId, newStatus, kind);
        } else {
            log.debug("Unhandled Braintree webhook kind: {}", kind);
        }
    }

    private void updateIntent(String providerPaymentId, PaymentIntentStatus newStatus, String kind) {
        Optional<PaymentIntent> intentOpt = paymentIntentRepository
                .findByProviderPaymentId(providerPaymentId);

        if (intentOpt.isEmpty()) {
            log.debug("No PaymentIntent found for Braintree transaction id={}", providerPaymentId);
            return;
        }

        PaymentIntent intent = intentOpt.get();
        if (intent.getStatus() != newStatus) {
            intent.setStatus(newStatus);
            paymentIntentRepository.save(intent);
            log.info("Reconciled PaymentIntent {} -> {} via Braintree webhook ({})",
                    intent.getId(), newStatus, kind);
        }
    }

    private boolean isVerificationConfigured() {
        return merchantId != null && !merchantId.isBlank()
                && publicKey  != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }

    private BraintreeGateway buildGateway() {
        return new BraintreeGateway(
                sandbox ? Environment.SANDBOX : Environment.PRODUCTION,
                merchantId, publicKey, privateKey);
    }

}
