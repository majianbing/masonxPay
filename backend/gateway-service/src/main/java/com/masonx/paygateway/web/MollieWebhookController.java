package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.webhook.ProcessedWebhookEvent;
import com.masonx.paygateway.domain.webhook.ProcessedWebhookEventRepository;
import com.masonx.paygateway.provider.MolliePaymentProviderService;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.MollieCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * Receives Mollie webhook notifications and reconciles payment intent status.
 *
 * ── Mollie webhook security model ──────────────────────────────────────────────
 * Mollie does NOT sign webhook payloads with HMAC. Instead their security model is:
 *   1. Mollie POSTs the payment ID as a form param: id=tr_xxxxx
 *   2. The recipient fetches the payment from Mollie's authenticated API to verify
 *      it exists and get its real status.
 *   3. The API call itself (requiring the merchant's secret API key) IS the verification.
 *      An attacker cannot forge a valid Mollie payment that passes the API fetch.
 *
 * Guards enforced (consistent with project webhook security rules):
 *   - Reject if 'id' param is missing/blank (analogous to missing signature header)
 *   - Reject if payment cannot be fetched from Mollie's API (verification failure)
 *   - Never process an unverified payload
 *
 * Register this URL in your Mollie dashboard: POST /api/v1/providers/mollie/webhook
 *
 * Events handled (all terminal states):
 *   paid → SUCCEEDED, failed → FAILED, expired → FAILED,
 *   canceled → CANCELED, authorized → REQUIRES_CAPTURE
 */
@RestController
@RequestMapping("/api/v1/providers/mollie")
public class MollieWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MollieWebhookController.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final ProcessedWebhookEventRepository processedEventRepository;
    private final CredentialsCodec credentialsCodec;
    private final MolliePaymentProviderService mollieService;

    public MollieWebhookController(PaymentIntentRepository paymentIntentRepository,
                                   ProviderAccountRepository providerAccountRepository,
                                   ProcessedWebhookEventRepository processedEventRepository,
                                   CredentialsCodec credentialsCodec,
                                   MolliePaymentProviderService mollieService) {
        this.paymentIntentRepository  = paymentIntentRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.processedEventRepository = processedEventRepository;
        this.credentialsCodec         = credentialsCodec;
        this.mollieService            = mollieService;
    }

    /**
     * Mollie sends a form-urlencoded POST with a single 'id' parameter.
     * Verification is done by fetching the payment from Mollie's API using
     * the merchant's secret API key (the API key IS the verification secret).
     */
    @PostMapping(value = "/webhook", consumes = { "application/x-www-form-urlencoded", "application/json", "*/*" })
    public ResponseEntity<Void> handleWebhook(
            @RequestParam(value = "id", required = false) String id) {

        // Guard 1: payment ID must be present (analogous to "signature header absent")
        if (id == null || id.isBlank()) {
            log.warn("Mollie webhook missing 'id' parameter — rejecting");
            return ResponseEntity.badRequest().build();
        }

        // Idempotency: deduplicate retries
        try {
            processedEventRepository.save(new ProcessedWebhookEvent("MOLLIE", id));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok().build(); // already processed
        }

        // Find the local payment intent by Mollie payment ID
        Optional<PaymentIntent> intentOpt = paymentIntentRepository.findByProviderPaymentId(id);
        if (intentOpt.isEmpty()) {
            // Could be from a different merchant or an old test payment — log at debug level only
            log.debug("Mollie webhook: no PaymentIntent found for id={}", id);
            return ResponseEntity.ok().build();
        }

        PaymentIntent intent = intentOpt.get();

        // Look up the connector account to get the merchant's API key
        if (intent.getConnectorAccountId() == null) {
            log.warn("Mollie webhook: PaymentIntent {} has no connectorAccountId", intent.getId());
            return ResponseEntity.badRequest().build();
        }

        ProviderAccount account = providerAccountRepository.findById(intent.getConnectorAccountId()).orElse(null);
        if (account == null) {
            log.warn("Mollie webhook: connector account {} not found for intent {}",
                    intent.getConnectorAccountId(), intent.getId());
            return ResponseEntity.badRequest().build();
        }

        MollieCredentials creds = (MollieCredentials) credentialsCodec.decode(account);

        // Guard 2: verify by fetching the payment from Mollie's authenticated API
        // If this fails (invalid id, revoked key, etc.) we reject the webhook
        JsonNode payment = mollieService.fetchPayment(id, creds.apiKey());
        if (payment == null) {
            log.warn("Mollie webhook: could not fetch payment {} from Mollie API — rejecting", id);
            return ResponseEntity.badRequest().build();
        }

        String mollieStatus = payment.path("status").asText("");
        PaymentIntentStatus newStatus = MolliePaymentProviderService.mapStatus(mollieStatus);

        if (newStatus == null) {
            log.debug("Mollie webhook: payment {} status '{}' is not terminal — no action", id, mollieStatus);
            return ResponseEntity.ok().build();
        }

        if (intent.getStatus() != newStatus) {
            intent.setStatus(newStatus);
            paymentIntentRepository.save(intent);
            log.info("Reconciled PaymentIntent {} → {} via Mollie webhook ({})",
                    intent.getId(), newStatus, mollieStatus);
        }

        return ResponseEntity.ok().build();
    }
}
