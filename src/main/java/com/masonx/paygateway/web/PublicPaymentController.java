package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.merchant.Merchant;
import com.masonx.paygateway.domain.merchant.MerchantRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.StripePaymentProviderService;
import com.masonx.paygateway.service.EncryptionService;
import com.masonx.paygateway.service.RoutingEngine;
import com.masonx.paygateway.web.dto.PublicCheckoutRequest;
import com.masonx.paygateway.web.dto.PublicCheckoutResponse;
import com.masonx.paygateway.web.dto.PublicPaymentLinkInfo;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/pub/pay")
public class PublicPaymentController {

    private final PaymentLinkRepository paymentLinkRepository;
    private final MerchantRepository merchantRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final EncryptionService encryptionService;
    private final RoutingEngine routingEngine;
    private final StripePaymentProviderService stripeProvider;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;

    public PublicPaymentController(PaymentLinkRepository paymentLinkRepository,
                                   MerchantRepository merchantRepository,
                                   ProviderAccountRepository providerAccountRepository,
                                   EncryptionService encryptionService,
                                   RoutingEngine routingEngine,
                                   StripePaymentProviderService stripeProvider,
                                   PaymentIntentRepository paymentIntentRepository,
                                   PaymentRequestRepository paymentRequestRepository) {
        this.paymentLinkRepository = paymentLinkRepository;
        this.merchantRepository = merchantRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.encryptionService = encryptionService;
        this.routingEngine = routingEngine;
        this.stripeProvider = stripeProvider;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
    }

    /** Returns payment link details including the merchant's publishable key for Stripe.js. */
    @GetMapping("/{token}")
    public ResponseEntity<PublicPaymentLinkInfo> info(@PathVariable String token) {
        PaymentLink link = findActiveLink(token);

        Merchant merchant = merchantRepository.findById(link.getMerchantId()).orElse(null);
        String merchantName = merchant != null ? merchant.getName() : "";

        // Resolve provider to know which connector's publishable key to return
        PaymentProvider provider = routingEngine.resolve(
                link.getMerchantId(), link.getAmount(), link.getCurrency(), null, "card");

        String publishableKey = providerAccountRepository
                .findByMerchantIdAndProviderAndModeAndPrimaryTrueAndStatus(
                        link.getMerchantId(), provider, link.getMode(), ProviderAccountStatus.ACTIVE)
                .map(ProviderAccount::getEncryptedPublishableKey)
                .filter(k -> k != null && !k.isBlank())
                .map(encryptionService::decrypt)
                .orElse(null);

        return ResponseEntity.ok(new PublicPaymentLinkInfo(
                link.getToken(),
                link.getTitle(),
                link.getDescription(),
                link.getAmount(),
                link.getCurrency().toUpperCase(),
                link.getMode().name(),
                merchantName,
                publishableKey,
                true
        ));
    }

    /** Processes a card payment for a payment link using a Stripe.js payment method token. */
    @PostMapping("/{token}/checkout")
    public ResponseEntity<PublicCheckoutResponse> checkout(
            @PathVariable String token,
            @Valid @RequestBody PublicCheckoutRequest req) {

        PaymentLink link = findActiveLink(token);

        PaymentProvider provider = routingEngine.resolve(
                link.getMerchantId(), link.getAmount(), link.getCurrency(), null, "card");

        Optional<ProviderAccount> connectorOpt = providerAccountRepository
                .findByMerchantIdAndProviderAndModeAndPrimaryTrueAndStatus(
                        link.getMerchantId(), provider, link.getMode(), ProviderAccountStatus.ACTIVE);

        if (connectorOpt.isEmpty()) {
            return ResponseEntity.ok(new PublicCheckoutResponse(
                    false, "FAILED", null,
                    "connector_not_configured",
                    "No active connector found for this merchant.",
                    null));
        }

        String secretKey = encryptionService.decrypt(connectorOpt.get().getEncryptedSecretKey());
        String idempotencyKey = "pl-" + link.getId() + "-" + UUID.randomUUID();

        // Persist payment intent
        PaymentIntent intent = new PaymentIntent();
        intent.setMerchantId(link.getMerchantId());
        intent.setMode(link.getMode());
        intent.setAmount(link.getAmount());
        intent.setCurrency(link.getCurrency());
        intent.setIdempotencyKey(idempotencyKey);
        intent.setResolvedProvider(provider);
        intent.setStatus(PaymentIntentStatus.PROCESSING);
        PaymentIntent savedIntent = paymentIntentRepository.save(intent);

        ChargeResult result = stripeProvider.charge(new ChargeRequest(
                savedIntent.getId(),
                link.getAmount(),
                link.getCurrency(),
                "card",
                req.paymentMethodId(),
                idempotencyKey,
                secretKey
        ));

        // Update intent
        savedIntent.setStatus(result.success() ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
        savedIntent.setProviderPaymentId(result.providerPaymentId());
        paymentIntentRepository.save(savedIntent);

        // Persist attempt
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

        String redirectUrl = result.success() ? link.getRedirectUrl() : null;

        return ResponseEntity.ok(new PublicCheckoutResponse(
                result.success(),
                result.success() ? "SUCCEEDED" : "FAILED",
                savedIntent.getId(),
                result.failureCode(),
                result.failureMessage(),
                redirectUrl
        ));
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
