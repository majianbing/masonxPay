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
import com.masonx.paygateway.provider.PaystackPaymentProviderService;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.PaystackCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/providers/paystack")
public class PaystackWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaystackWebhookController.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final ProcessedWebhookEventRepository processedEventRepository;
    private final CredentialsCodec credentialsCodec;
    private final PaystackPaymentProviderService paystackService;
    private final ObjectMapper objectMapper;

    public PaystackWebhookController(PaymentIntentRepository paymentIntentRepository,
                                     ProviderAccountRepository providerAccountRepository,
                                     ProcessedWebhookEventRepository processedEventRepository,
                                     CredentialsCodec credentialsCodec,
                                     PaystackPaymentProviderService paystackService,
                                     ObjectMapper objectMapper) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.processedEventRepository = processedEventRepository;
        this.credentialsCodec = credentialsCodec;
        this.paystackService = paystackService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "x-paystack-signature", required = false) String signature) {

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("Paystack webhook received invalid JSON");
            return ResponseEntity.badRequest().build();
        }

        String reference = event.path("data").path("reference").asText(null);
        if (reference == null || reference.isBlank()) {
            log.warn("Paystack webhook missing data.reference");
            return ResponseEntity.badRequest().build();
        }
        String eventId = event.path("event").asText("event") + ":" + reference;

        Optional<PaymentIntent> intentOpt = paymentIntentRepository.findByProviderPaymentId(reference);
        if (intentOpt.isEmpty()) {
            log.debug("Paystack webhook: no PaymentIntent found for reference={}", reference);
            return ResponseEntity.ok().build();
        }

        PaymentIntent intent = intentOpt.get();
        if (intent.getConnectorAccountId() == null) {
            log.warn("Paystack webhook: PaymentIntent {} has no connectorAccountId", intent.getId());
            return ResponseEntity.badRequest().build();
        }

        ProviderAccount account = providerAccountRepository.findById(intent.getConnectorAccountId()).orElse(null);
        if (account == null) {
            log.warn("Paystack webhook: connector account {} not found for intent {}",
                    intent.getConnectorAccountId(), intent.getId());
            return ResponseEntity.badRequest().build();
        }
        if (account.getProvider() != PaymentProvider.PAYSTACK) {
            log.warn("Paystack webhook: connector account {} is provider {}", account.getId(), account.getProvider());
            return ResponseEntity.badRequest().build();
        }

        PaystackCredentials creds = (PaystackCredentials) credentialsCodec.decode(account);
        if (signature == null || !constantTimeEquals(hmacSha512Hex(payload, creds.secretKey()), signature)) {
            log.warn("Paystack webhook signature mismatch");
            return ResponseEntity.badRequest().build();
        }

        try {
            processedEventRepository.save(new ProcessedWebhookEvent("PAYSTACK", eventId));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok().build();
        }

        JsonNode verified = paystackService.verifyByReference(reference, creds.secretKey());
        if (verified == null || !verified.path("status").asBoolean(false)) {
            log.warn("Paystack webhook: provider verification failed for reference={}", reference);
            return ResponseEntity.badRequest().build();
        }

        PaymentIntentStatus newStatus = PaystackPaymentProviderService.mapStatus(
                verified.path("data").path("status").asText(""));
        if (newStatus == null) {
            log.debug("Paystack webhook: reference={} is not terminal", reference);
            return ResponseEntity.ok().build();
        }

        if (intent.getStatus() != newStatus) {
            intent.setStatus(newStatus);
            paymentIntentRepository.save(intent);
            log.info("Reconciled PaymentIntent {} -> {} via Paystack webhook", intent.getId(), newStatus);
        }

        return ResponseEntity.ok().build();
    }

    private static String hmacSha512Hex(String payload, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute Paystack webhook signature", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
