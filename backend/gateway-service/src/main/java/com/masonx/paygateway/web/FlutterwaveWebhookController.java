package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.payment.PaymentIntent;
import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.payment.PaymentIntentStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.webhook.ProcessedWebhookEvent;
import com.masonx.paygateway.domain.webhook.ProcessedWebhookEventRepository;
import com.masonx.paygateway.provider.FlutterwavePaymentProviderService;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.FlutterwaveCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/providers/flutterwave")
public class FlutterwaveWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FlutterwaveWebhookController.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final ProcessedWebhookEventRepository processedEventRepository;
    private final CredentialsCodec credentialsCodec;
    private final FlutterwavePaymentProviderService flutterwaveService;
    private final ObjectMapper objectMapper;

    public FlutterwaveWebhookController(PaymentIntentRepository paymentIntentRepository,
                                        ProviderAccountRepository providerAccountRepository,
                                        ProcessedWebhookEventRepository processedEventRepository,
                                        CredentialsCodec credentialsCodec,
                                        FlutterwavePaymentProviderService flutterwaveService,
                                        ObjectMapper objectMapper) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.processedEventRepository = processedEventRepository;
        this.credentialsCodec = credentialsCodec;
        this.flutterwaveService = flutterwaveService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "verif-hash", required = false) String verificationHash) {

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Flutterwave webhook received invalid JSON");
            return ResponseEntity.badRequest().build();
        }

        String txRef = event.path("data").path("tx_ref").asText(null);
        String eventId = event.path("id").asText(null);
        if (eventId == null || eventId.isBlank()) {
            eventId = event.path("event").asText("event") + ":" + txRef;
        }
        if (txRef == null || txRef.isBlank()) {
            log.warn("Flutterwave webhook missing data.tx_ref");
            return ResponseEntity.badRequest().build();
        }

        Optional<PaymentIntent> intentOpt = paymentIntentRepository.findByProviderPaymentId(txRef);
        if (intentOpt.isEmpty()) {
            log.debug("Flutterwave webhook: no PaymentIntent found for tx_ref={}", txRef);
            return ResponseEntity.ok().build();
        }

        PaymentIntent intent = intentOpt.get();
        if (intent.getConnectorAccountId() == null) {
            log.warn("Flutterwave webhook: PaymentIntent {} has no connectorAccountId", intent.getId());
            return ResponseEntity.badRequest().build();
        }

        ProviderAccount account = providerAccountRepository.findById(intent.getConnectorAccountId()).orElse(null);
        if (account == null) {
            log.warn("Flutterwave webhook: connector account {} not found for intent {}",
                    intent.getConnectorAccountId(), intent.getId());
            return ResponseEntity.badRequest().build();
        }
        if (account.getProvider() != PaymentProvider.FLUTTERWAVE) {
            log.warn("Flutterwave webhook: connector account {} is provider {}", account.getId(), account.getProvider());
            return ResponseEntity.badRequest().build();
        }

        FlutterwaveCredentials creds = (FlutterwaveCredentials) credentialsCodec.decode(account);
        if (creds.webhookHash() == null || creds.webhookHash().isBlank()) {
            log.warn("Flutterwave webhook received but webhook hash is not configured - rejecting");
            return ResponseEntity.badRequest().build();
        }
        if (verificationHash == null || !constantTimeEquals(creds.webhookHash(), verificationHash)) {
            log.warn("Flutterwave webhook verification hash mismatch");
            return ResponseEntity.badRequest().build();
        }

        try {
            processedEventRepository.save(new ProcessedWebhookEvent("FLUTTERWAVE", eventId));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok().build();
        }

        JsonNode verified = flutterwaveService.verifyByReference(txRef, creds.secretKey());
        if (verified == null || !"success".equalsIgnoreCase(verified.path("status").asText(""))) {
            log.warn("Flutterwave webhook: provider verification failed for tx_ref={}", txRef);
            return ResponseEntity.badRequest().build();
        }

        PaymentIntentStatus newStatus = FlutterwavePaymentProviderService.mapStatus(
                verified.path("data").path("status").asText(""));
        if (newStatus == null) {
            log.debug("Flutterwave webhook: tx_ref={} is not terminal", txRef);
            return ResponseEntity.ok().build();
        }

        if (intent.getStatus() != newStatus) {
            intent.setStatus(newStatus);
            paymentIntentRepository.save(intent);
            log.info("Reconciled PaymentIntent {} -> {} via Flutterwave webhook", intent.getId(), newStatus);
        }

        return ResponseEntity.ok().build();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
