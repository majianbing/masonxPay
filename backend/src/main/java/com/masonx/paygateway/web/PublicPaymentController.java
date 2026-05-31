package com.masonx.paygateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.outbox.OutboxEvent;
import com.masonx.paygateway.domain.outbox.OutboxEventRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.masonx.paygateway.service.PaymentTokenService;
import com.masonx.paygateway.service.RoutingEngine;
import com.masonx.paygateway.service.routing.RoutingContext;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import com.masonx.paygateway.web.dto.PublicCheckoutRequest;
import com.masonx.paygateway.web.dto.PublicCheckoutResponse;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/pub/pay")
public class PublicPaymentController {

    private static final Logger log = LoggerFactory.getLogger(PublicPaymentController.class);

    private final PaymentLinkRepository        paymentLinkRepository;
    private final ProviderAccountRepository    providerAccountRepository;
    private final CredentialsCodec             credentialsCodec;
    private final PaymentProviderDispatcher    dispatcher;
    private final PaymentIntentRepository      paymentIntentRepository;
    private final PaymentRequestRepository     paymentRequestRepository;
    private final PaymentTokenService          paymentTokenService;
    private final RoutingEngine                routingEngine;
    private final OutboxEventRepository        outboxEventRepository;
    private final ObjectMapper                 objectMapper;
    private final PaymentMetrics               metrics;

    @Value("${app.pay-base-url:http://localhost:3000}")
    private String payBaseUrl;

    public PublicPaymentController(PaymentLinkRepository paymentLinkRepository,
                                   ProviderAccountRepository providerAccountRepository,
                                   CredentialsCodec credentialsCodec,
                                   PaymentProviderDispatcher dispatcher,
                                   PaymentIntentRepository paymentIntentRepository,
                                   PaymentRequestRepository paymentRequestRepository,
                                   PaymentTokenService paymentTokenService,
                                   RoutingEngine routingEngine,
                                   OutboxEventRepository outboxEventRepository,
                                   ObjectMapper objectMapper,
                                   PaymentMetrics metrics) {
        this.paymentLinkRepository     = paymentLinkRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.credentialsCodec          = credentialsCodec;
        this.dispatcher                = dispatcher;
        this.paymentIntentRepository   = paymentIntentRepository;
        this.paymentRequestRepository  = paymentRequestRepository;
        this.paymentTokenService       = paymentTokenService;
        this.routingEngine             = routingEngine;
        this.outboxEventRepository     = outboxEventRepository;
        this.objectMapper              = objectMapper;
        this.metrics                   = metrics;
    }

    // ── Card / synchronous checkout (Square, Braintree, Stripe card) ──────────

    /**
     * Submits a gateway token (from /pub/tokenize) to charge the customer.
     *
     * If the provider requires 3DS/SCA authentication, returns status REQUIRES_ACTION
     * with a providerAction descriptor. The SDK opens an iframe overlay (redirect_url)
     * or calls stripe.handleNextAction() (stripe_sdk) and polls /payment-status when done.
     *
     * The payment link is claimed atomically before the remote charge to prevent
     * double-payment. Released back to ACTIVE on failure or cancellation.
     */
    @PostMapping("/{token}/checkout")
    public ResponseEntity<PublicCheckoutResponse> checkout(
            @PathVariable String token,
            @Valid @RequestBody PublicCheckoutRequest req) {

        PaymentLink link = findActiveLink(token);

        // Atomically claim the link before the remote charge — prevents double-payment.
        if (paymentLinkRepository.claimLink(token) == 0) {
            throw new IllegalStateException("This payment link has already been paid");
        }

        PaymentToken paymentToken = paymentTokenService.consume(req.gatewayToken());

        ProviderAccount account = providerAccountRepository.findById(paymentToken.getAccountId())
                .orElseThrow(() -> new IllegalStateException("Connector account not found"));
        if (!routingEngine.supportsCapabilities(account, linkContext(link))) {
            paymentLinkRepository.releaseLink(token);
            throw new IllegalStateException("Selected connector does not support this payment link");
        }

        ProviderCredentials creds = credentialsCodec.decode(account);
        PaymentProvider provider = PaymentProvider.valueOf(paymentToken.getProvider());
        String idempotencyKey = "pl-" + link.getId() + "-" + UUID.randomUUID();

        PaymentIntent intent = new PaymentIntent();
        intent.setMerchantId(link.getMerchantId());
        intent.setMode(link.getMode());
        intent.setAmount(link.getAmount());
        intent.setCurrency(link.getCurrency());
        intent.setIdempotencyKey(idempotencyKey);
        intent.setResolvedProvider(provider);
        intent.setConnectorAccountId(account.getId());
        intent.setPaymentMethodType("card");
        intent.setStatus(PaymentIntentStatus.PROCESSING);
        PaymentIntent savedIntent = paymentIntentRepository.save(intent);

        // 3DS return URL: /pay/3ds-return?linkToken={token}
        // Stripe appends payment_intent_client_secret and redirect_status automatically.
        String returnUrl = payBaseUrl + "/pay/3ds-return?linkToken=" + token;

        long chargeStart = System.currentTimeMillis();
        ChargeResult result = dispatcher.charge(provider, new ChargeRequest(
                savedIntent.getId(), link.getAmount(), link.getCurrency(),
                "card", paymentToken.getProviderPmId(),
                null,
                idempotencyKey, null, null, null, returnUrl
        ), creds);
        metrics.recordChargeLatency(provider.name(), System.currentTimeMillis() - chargeStart);

        // 3DS / SCA required — park the intent and let the SDK handle the challenge
        if (result.requiresAction()) {
            savedIntent.setStatus(PaymentIntentStatus.REQUIRES_ACTION);
            savedIntent.setProviderPaymentId(result.providerPaymentId());
            savedIntent.setActionType(result.actionType());
            savedIntent.setActionUrl(result.actionUrl());
            paymentIntentRepository.save(savedIntent);
            // Link stays claimed during 3DS to prevent a parallel checkout attempt.
            // It will be released on cancel (/cancel-3ds) or stay paid on success.
            return ResponseEntity.ok(new PublicCheckoutResponse(
                    false, "REQUIRES_ACTION", savedIntent.getId(),
                    null, null, null,
                    new PublicCheckoutResponse.ProviderAction(
                            result.actionType(), result.actionUrl(), result.clientSecret())));
        }

        savedIntent.setStatus(result.success() ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
        savedIntent.setProviderPaymentId(result.providerPaymentId());
        paymentIntentRepository.save(savedIntent);

        if (!result.success()) {
            paymentLinkRepository.releaseLink(token);
        }

        metrics.recordIntentConfirmed(
                provider.name(),
                savedIntent.getStatus().name(),
                result.failureCode());

        PaymentRequest attempt = new PaymentRequest();
        attempt.setPaymentIntentId(savedIntent.getId());
        attempt.setAmount(link.getAmount());
        attempt.setCurrency(link.getCurrency());
        attempt.setPaymentMethodType("card");
        attempt.setStatus(result.success() ? PaymentRequestStatus.SUCCEEDED : PaymentRequestStatus.FAILED);
        attempt.setProviderRequestId(result.providerPaymentId());
        attempt.setFailureCode(result.failureCode());
        attempt.setFailureMessage(result.failureMessage());
        paymentRequestRepository.save(attempt);

        String eventType = result.success() ? "payment_intent.succeeded" : "payment_intent.failed";
        publishEvent(savedIntent, attempt, eventType);

        return ResponseEntity.ok(new PublicCheckoutResponse(
                result.success(),
                result.success() ? "SUCCEEDED" : "FAILED",
                savedIntent.getId(),
                result.failureCode(), result.failureMessage(),
                result.success() ? link.getRedirectUrl() : null,
                null));
    }

    // ── 3DS / SCA challenge support ───────────────────────────────────────────

    /**
     * Polls the current status of a payment intent after 3DS completes.
     * Called by the SDK after receiving the gw:3ds_complete postMessage from the return page,
     * or after stripe.handleNextAction() resolves.
     *
     * Returns a CheckoutResult shape the SDK can act on directly.
     */
    @GetMapping("/{token}/payment-status")
    public ResponseEntity<PublicCheckoutResponse> paymentStatus(
            @PathVariable String token,
            @RequestParam UUID piId) {

        PaymentLink link = findActiveLink(token);
        PaymentIntent intent = paymentIntentRepository.findById(piId)
                .filter(pi -> pi.getMerchantId().equals(link.getMerchantId()))
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

        boolean success = intent.getStatus() == PaymentIntentStatus.SUCCEEDED;

        // If 3DS completed successfully, finalize the link and write event/attempt records
        // only if we haven't already (idempotent — check for existing attempt records).
        if (success && paymentRequestRepository.findByPaymentIntentId(piId).isEmpty()) {
            PaymentRequest attempt = new PaymentRequest();
            attempt.setPaymentIntentId(piId);
            attempt.setAmount(intent.getAmount());
            attempt.setCurrency(intent.getCurrency());
            attempt.setPaymentMethodType("card");
            attempt.setStatus(PaymentRequestStatus.SUCCEEDED);
            attempt.setProviderRequestId(intent.getProviderPaymentId());
            paymentRequestRepository.save(attempt);
            publishEvent(intent, attempt, "payment_intent.succeeded");
            metrics.recordIntentConfirmed(
                    intent.getResolvedProvider() != null ? intent.getResolvedProvider().name() : "unknown",
                    PaymentIntentStatus.SUCCEEDED.name(),
                    null);
        }

        return ResponseEntity.ok(new PublicCheckoutResponse(
                success, intent.getStatus().name(), intent.getId(),
                null, null,
                success ? link.getRedirectUrl() : null,
                null));
    }

    /**
     * Cancels a REQUIRES_ACTION payment intent when the customer clicks "Cancel" in the
     * 3DS overlay. Releases the payment link back to ACTIVE so the customer can retry.
     */
    @PostMapping("/{token}/cancel-3ds")
    public ResponseEntity<Void> cancel3ds(
            @PathVariable String token,
            @RequestParam UUID piId) {

        PaymentLink link = findActiveLink(token);
        PaymentIntent intent = paymentIntentRepository.findById(piId)
                .filter(pi -> pi.getMerchantId().equals(link.getMerchantId()))
                .filter(pi -> pi.getStatus() == PaymentIntentStatus.REQUIRES_ACTION)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment not found or not awaiting authentication"));

        // Cancel at provider (best-effort — the PI may already be expired at Stripe's side)
        if (intent.getProviderPaymentId() != null && intent.getConnectorAccountId() != null) {
            try {
                ProviderCredentials creds = credentialsCodec.decode(
                        providerAccountRepository.findById(intent.getConnectorAccountId()).orElseThrow());
                dispatcher.cancelAtProvider(intent.getResolvedProvider(),
                        intent.getProviderPaymentId(), creds);
            } catch (Exception e) {
                log.warn("3DS cancel: could not cancel at provider for intent {}: {}", piId, e.getMessage());
            }
        }

        intent.setStatus(PaymentIntentStatus.CANCELED);
        paymentIntentRepository.save(intent);
        paymentLinkRepository.releaseLink(token);

        return ResponseEntity.ok().build();
    }

    // ── Stripe redirect-based methods (Amazon Pay, iDEAL, Sofort, etc.) ───────

    @PostMapping("/{token}/prepare-stripe")
    public ResponseEntity<Map<String, String>> prepareStripe(@PathVariable String token) {
        PaymentLink link = findActiveLink(token);

        ProviderAccount account = stripeAccountForLink(link);

        if (!(credentialsCodec.decode(account) instanceof StripeCredentials stripe) || stripe.secretKey() == null) {
            throw new IllegalStateException("Stripe connector is missing a secret key");
        }

        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(link.getAmount())
                    .setCurrency(link.getCurrency().toLowerCase())
                    .setAutomaticPaymentMethods(
                            PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                    .setEnabled(true)
                                    .build())
                    .putMetadata("linkToken", token)
                    .putMetadata("merchantId", link.getMerchantId().toString())
                    .build();

            RequestOptions opts = RequestOptions.builder().setApiKey(stripe.secretKey()).build();
            com.stripe.model.PaymentIntent pi = com.stripe.model.PaymentIntent.create(params, opts);
            return ResponseEntity.ok(Map.of("clientSecret", pi.getClientSecret()));

        } catch (StripeException e) {
            throw new IllegalStateException("Failed to prepare Stripe checkout: " + e.getMessage(), e);
        }
    }

    /**
     * Called by the SDK when the customer returns from a redirect-based payment (Amazon Pay,
     * iDEAL, Sofort, etc.) OR from the 3DS return page after 3DS1 redirect completes.
     * Idempotent: if a record already exists for this Stripe PI, returns the existing result.
     */
    @GetMapping("/{token}/stripe-result")
    public ResponseEntity<PublicCheckoutResponse> stripeResult(
            @PathVariable String token,
            @RequestParam String piClientSecret) {

        PaymentLink link = findActiveLink(token);
        String piId = piClientSecret.split("_secret_")[0];

        // Idempotency: return existing record if already processed
        var existing = paymentIntentRepository.findByProviderPaymentId(piId);
        if (existing.isPresent()) {
            PaymentIntent intent = existing.get();
            boolean ok = intent.getStatus() == PaymentIntentStatus.SUCCEEDED;
            return ResponseEntity.ok(new PublicCheckoutResponse(
                    ok, intent.getStatus().name(), intent.getId(),
                    ok ? null : "payment_failed", ok ? null : "Payment did not succeed",
                    ok ? link.getRedirectUrl() : null, null));
        }

        ProviderAccount account = stripeAccountForLink(link);

        if (!(credentialsCodec.decode(account) instanceof StripeCredentials stripe) || stripe.secretKey() == null) {
            throw new IllegalStateException("Stripe connector is missing a secret key");
        }

        try {
            RequestOptions opts = RequestOptions.builder().setApiKey(stripe.secretKey()).build();
            PaymentIntentRetrieveParams retrieveParams = PaymentIntentRetrieveParams.builder()
                    .addExpand("payment_method")
                    .build();
            com.stripe.model.PaymentIntent pi =
                    com.stripe.model.PaymentIntent.retrieve(piId, retrieveParams, opts);
            boolean succeeded = "succeeded".equals(pi.getStatus());

            if (succeeded && paymentLinkRepository.claimLink(token) == 0) {
                return ResponseEntity.ok(new PublicCheckoutResponse(
                        true, "SUCCEEDED", null, null, null, link.getRedirectUrl(), null));
            }

            com.stripe.model.PaymentMethod pm = pi.getPaymentMethodObject();
            String pmType = (pm != null && pm.getType() != null)
                    ? pm.getType()
                    : (pi.getPaymentMethodTypes() != null && !pi.getPaymentMethodTypes().isEmpty()
                            ? pi.getPaymentMethodTypes().get(0) : "unknown");

            String failureCode = null;
            String failureMessage = null;
            if (!succeeded && pi.getLastPaymentError() != null) {
                failureCode = pi.getLastPaymentError().getCode();
                failureMessage = pi.getLastPaymentError().getMessage();
            }

            PaymentIntent intent = new PaymentIntent();
            intent.setMerchantId(link.getMerchantId());
            intent.setMode(link.getMode());
            intent.setAmount(link.getAmount());
            intent.setCurrency(link.getCurrency());
            intent.setIdempotencyKey("pi-redirect-" + piId);
            intent.setResolvedProvider(PaymentProvider.STRIPE);
            intent.setConnectorAccountId(account.getId());
            intent.setStatus(succeeded ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
            intent.setProviderPaymentId(piId);
            PaymentIntent savedIntent = paymentIntentRepository.save(intent);

            if (!succeeded) {
                paymentLinkRepository.releaseLink(token);
            }

            PaymentRequest attempt = new PaymentRequest();
            attempt.setPaymentIntentId(savedIntent.getId());
            attempt.setAmount(link.getAmount());
            attempt.setCurrency(link.getCurrency());
            attempt.setPaymentMethodType(pmType);
            attempt.setStatus(succeeded ? PaymentRequestStatus.SUCCEEDED : PaymentRequestStatus.FAILED);
            attempt.setProviderRequestId(piId);
            attempt.setFailureCode(failureCode);
            attempt.setFailureMessage(failureMessage);
            paymentRequestRepository.save(attempt);

            metrics.recordIntentConfirmed(
                    PaymentProvider.STRIPE.name(),
                    savedIntent.getStatus().name(),
                    failureCode);

            publishEvent(savedIntent, attempt, succeeded ? "payment_intent.succeeded" : "payment_intent.failed");

            return ResponseEntity.ok(new PublicCheckoutResponse(
                    succeeded, succeeded ? "SUCCEEDED" : "FAILED", savedIntent.getId(),
                    failureCode, failureMessage,
                    succeeded ? link.getRedirectUrl() : null, null));

        } catch (StripeException e) {
            throw new IllegalStateException("Failed to retrieve Stripe payment status: " + e.getMessage(), e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void publishEvent(PaymentIntent intent, PaymentRequest attempt, String eventType) {
        try {
            PaymentIntentResponse payload = PaymentIntentResponse.from(intent, List.of(attempt), objectMapper, null);
            String json = objectMapper.writeValueAsString(payload);
            outboxEventRepository.save(new OutboxEvent(intent.getMerchantId(), eventType, intent.getId(), json));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize outbox payload for event {} on public payment intent {}: {}",
                    eventType, intent.getId(), e.getMessage());
        }
    }

    private PaymentLink findActiveLink(String token) {
        PaymentLink link = paymentLinkRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("Payment link not found"));
        if (link.getStatus() == PaymentLinkStatus.INACTIVE || link.isExpired()) {
            throw new IllegalStateException("This payment link is no longer active");
        }
        return link;
    }

    private ProviderAccount stripeAccountForLink(PaymentLink link) {
        ProviderAccount account = (link.getPinnedConnectorId() != null)
                ? providerAccountRepository.findById(link.getPinnedConnectorId())
                        .orElseThrow(() -> new IllegalStateException("Pinned connector not found"))
                : routingEngine.resolveAccountForProvider(
                                link.getMerchantId(), PaymentProvider.STRIPE, link.getMode(), linkContext(link))
                        .orElseThrow(() -> new IllegalStateException("No eligible Stripe connector configured"));
        if (account.getProvider() != PaymentProvider.STRIPE) {
            throw new IllegalStateException("Selected connector is not a Stripe account");
        }
        if (!routingEngine.supportsCapabilities(account, linkContext(link))) {
            throw new IllegalStateException("Stripe connector does not support this payment link");
        }
        return account;
    }

    private RoutingContext linkContext(PaymentLink link) {
        return new RoutingContext(
                link.getMerchantId(),
                link.getMode(),
                link.getAmount(),
                link.getCurrency(),
                null,
                "card",
                CaptureMethod.AUTOMATIC,
                null,
                null,
                Map.of(),
                null,
                InstrumentSource.PROVIDER_TOKEN,
                null,
                null,
                null,
                null,
                null,
                null);
    }
}
