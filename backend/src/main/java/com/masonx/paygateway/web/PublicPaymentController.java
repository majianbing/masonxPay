package com.masonx.paygateway.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.event.PaymentGatewayEvent;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.provider.credentials.StripeCredentials;
import com.masonx.paygateway.service.PaymentTokenService;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import com.masonx.paygateway.web.dto.PublicCheckoutRequest;
import com.masonx.paygateway.web.dto.PublicCheckoutResponse;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/pub/pay")
public class PublicPaymentController {

    private final PaymentLinkRepository paymentLinkRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final CredentialsCodec credentialsCodec;
    private final PaymentProviderDispatcher dispatcher;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentTokenService paymentTokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public PublicPaymentController(PaymentLinkRepository paymentLinkRepository,
                                   ProviderAccountRepository providerAccountRepository,
                                   CredentialsCodec credentialsCodec,
                                   PaymentProviderDispatcher dispatcher,
                                   PaymentIntentRepository paymentIntentRepository,
                                   PaymentRequestRepository paymentRequestRepository,
                                   PaymentTokenService paymentTokenService,
                                   ApplicationEventPublisher eventPublisher,
                                   ObjectMapper objectMapper) {
        this.paymentLinkRepository = paymentLinkRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.credentialsCodec = credentialsCodec;
        this.dispatcher = dispatcher;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.paymentTokenService = paymentTokenService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    // ── Card / synchronous checkout (Square, Braintree, Stripe card) ──────────

    @PostMapping("/{token}/checkout")
    public ResponseEntity<PublicCheckoutResponse> checkout(
            @PathVariable String token,
            @Valid @RequestBody PublicCheckoutRequest req) {

        PaymentLink link = findActiveLink(token);

        // Atomically claim the link before the remote charge — prevents double-payment
        // without holding a DB connection across the network call.
        if (paymentLinkRepository.claimLink(token) == 0) {
            throw new IllegalStateException("This payment link has already been paid");
        }

        PaymentToken paymentToken = paymentTokenService.consume(req.gatewayToken());

        ProviderAccount account = providerAccountRepository.findById(paymentToken.getAccountId())
                .orElseThrow(() -> new IllegalStateException("Connector account not found"));

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
        intent.setStatus(PaymentIntentStatus.PROCESSING);
        PaymentIntent savedIntent = paymentIntentRepository.save(intent);

        ChargeResult result = dispatcher.charge(provider, new ChargeRequest(
                savedIntent.getId(),
                link.getAmount(),
                link.getCurrency(),
                "card",
                paymentToken.getProviderPmId(),
                idempotencyKey
        ), creds);

        savedIntent.setStatus(result.success() ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
        savedIntent.setProviderPaymentId(result.providerPaymentId());
        paymentIntentRepository.save(savedIntent);

        // Release the link back to ACTIVE on failure so the customer can retry with a different card.
        if (!result.success()) {
            paymentLinkRepository.releaseLink(token);
        }

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

        // Publish event so webhook endpoints receive delivery
        String eventType = result.success() ? "payment_intent.succeeded" : "payment_intent.failed";
        publishEvent(savedIntent, attempt, eventType);

        return ResponseEntity.ok(new PublicCheckoutResponse(
                result.success(),
                result.success() ? "SUCCEEDED" : "FAILED",
                savedIntent.getId(),
                result.failureCode(),
                result.failureMessage(),
                result.success() ? link.getRedirectUrl() : null
        ));
    }

    // ── Stripe redirect-based methods (Amazon Pay, iDEAL, Sofort, etc.) ───────

    /**
     * Creates a Stripe PaymentIntent with automatic_payment_methods enabled and returns its
     * client_secret to the SDK. The SDK uses this to initialize the Stripe Payment Element,
     * which shows all methods the merchant has enabled (card, wallets, local methods, etc.).
     *
     * Called once per checkout session when the customer selects Stripe. Cached by the SDK
     * so switching providers back-and-forth does not create multiple PIs.
     */
    @PostMapping("/{token}/prepare-stripe")
    public ResponseEntity<Map<String, String>> prepareStripe(@PathVariable String token) {
        PaymentLink link = findActiveLink(token);

        ProviderAccount account = (link.getPinnedConnectorId() != null)
                ? providerAccountRepository.findById(link.getPinnedConnectorId())
                        .orElseThrow(() -> new IllegalStateException("Pinned connector not found"))
                : providerAccountRepository
                        .findAllByMerchantIdAndProviderAndModeAndStatus(
                                link.getMerchantId(), PaymentProvider.STRIPE, link.getMode(), ProviderAccountStatus.ACTIVE)
                        .stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("No active Stripe connector configured"));

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
     * iDEAL, Sofort, etc.). Stripe appends payment_intent_client_secret to the return URL;
     * the SDK passes it here so we can retrieve the final status from Stripe server-side,
     * create our DB record, and return a CheckoutResult.
     *
     * Idempotent: if a record already exists for this Stripe PI (e.g. page refresh), returns
     * the existing result without creating a duplicate.
     */
    @GetMapping("/{token}/stripe-result")
    public ResponseEntity<PublicCheckoutResponse> stripeResult(
            @PathVariable String token,
            @RequestParam String piClientSecret) {

        PaymentLink link = findActiveLink(token);

        // Stripe client secret format: "pi_xxx_secret_yyy" — extract the PI ID
        String piId = piClientSecret.split("_secret_")[0];

        // Idempotency: return existing record if we've already processed this PI
        var existing = paymentIntentRepository.findByProviderPaymentId(piId);
        if (existing.isPresent()) {
            PaymentIntent intent = existing.get();
            boolean ok = intent.getStatus() == PaymentIntentStatus.SUCCEEDED;
            return ResponseEntity.ok(new PublicCheckoutResponse(
                    ok, intent.getStatus().name(), intent.getId(),
                    ok ? null : "payment_failed", ok ? null : "Payment did not succeed",
                    ok ? link.getRedirectUrl() : null));
        }

        // Retrieve the PI from Stripe to get authoritative status
        ProviderAccount account = (link.getPinnedConnectorId() != null)
                ? providerAccountRepository.findById(link.getPinnedConnectorId())
                        .orElseThrow(() -> new IllegalStateException("Pinned connector not found"))
                : providerAccountRepository
                        .findAllByMerchantIdAndProviderAndModeAndStatus(
                                link.getMerchantId(), PaymentProvider.STRIPE, link.getMode(), ProviderAccountStatus.ACTIVE)
                        .stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("No active Stripe connector configured"));

        if (!(credentialsCodec.decode(account) instanceof StripeCredentials stripe) || stripe.secretKey() == null) {
            throw new IllegalStateException("Stripe connector is missing a secret key");
        }

        try {
            RequestOptions opts = RequestOptions.builder().setApiKey(stripe.secretKey()).build();
            com.stripe.model.PaymentIntent pi = com.stripe.model.PaymentIntent.retrieve(piId, opts);
            boolean succeeded = "succeeded".equals(pi.getStatus());

            // Claim the link atomically — if someone else already claimed it (e.g. webhook),
            // skip record creation and return success.
            if (succeeded && paymentLinkRepository.claimLink(token) == 0) {
                return ResponseEntity.ok(new PublicCheckoutResponse(
                        true, "SUCCEEDED", null, null, null, link.getRedirectUrl()));
            }

            // Determine payment method type from the PI
            String pmType = (pi.getPaymentMethodTypes() != null && !pi.getPaymentMethodTypes().isEmpty())
                    ? pi.getPaymentMethodTypes().get(0) : "unknown";

            // Failure details
            String failureCode = null;
            String failureMessage = null;
            if (!succeeded && pi.getLastPaymentError() != null) {
                failureCode = pi.getLastPaymentError().getCode();
                failureMessage = pi.getLastPaymentError().getMessage();
            }

            // Create our payment intent record
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

            // Release the link for retry on failure
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

            publishEvent(savedIntent, attempt, succeeded ? "payment_intent.succeeded" : "payment_intent.failed");

            return ResponseEntity.ok(new PublicCheckoutResponse(
                    succeeded, succeeded ? "SUCCEEDED" : "FAILED", savedIntent.getId(),
                    failureCode, failureMessage,
                    succeeded ? link.getRedirectUrl() : null));

        } catch (StripeException e) {
            throw new IllegalStateException("Failed to retrieve Stripe payment status: " + e.getMessage(), e);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private void publishEvent(PaymentIntent intent, PaymentRequest attempt, String eventType) {
        try {
            PaymentIntentResponse payload = PaymentIntentResponse.from(intent, List.of(attempt), objectMapper, null);
            String json = objectMapper.writeValueAsString(payload);
            eventPublisher.publishEvent(
                    new PaymentGatewayEvent(this, intent.getMerchantId(), eventType, intent.getId(), json));
        } catch (JsonProcessingException e) {
            // non-critical — log and move on
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
}
