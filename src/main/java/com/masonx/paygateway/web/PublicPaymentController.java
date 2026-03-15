package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.StripePaymentProviderService;
import com.masonx.paygateway.service.EncryptionService;
import com.masonx.paygateway.service.PaymentTokenService;
import com.masonx.paygateway.web.dto.PublicCheckoutRequest;
import com.masonx.paygateway.web.dto.PublicCheckoutResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/pub/pay")
public class PublicPaymentController {

    private final PaymentLinkRepository paymentLinkRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final EncryptionService encryptionService;
    private final StripePaymentProviderService stripeProvider;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentTokenService paymentTokenService;

    public PublicPaymentController(PaymentLinkRepository paymentLinkRepository,
                                   ProviderAccountRepository providerAccountRepository,
                                   EncryptionService encryptionService,
                                   StripePaymentProviderService stripeProvider,
                                   PaymentIntentRepository paymentIntentRepository,
                                   PaymentRequestRepository paymentRequestRepository,
                                   PaymentTokenService paymentTokenService) {
        this.paymentLinkRepository = paymentLinkRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.encryptionService = encryptionService;
        this.stripeProvider = stripeProvider;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.paymentTokenService = paymentTokenService;
    }

    /**
     * Processes a card payment for a payment link.
     * Accepts a gateway token (gw_tok_xxx) which resolves to the pre-selected
     * connector account and provider PM ID — no provider details leak to the merchant.
     */
    @PostMapping("/{token}/checkout")
    public ResponseEntity<PublicCheckoutResponse> checkout(
            @PathVariable String token,
            @Valid @RequestBody PublicCheckoutRequest req) {

        PaymentLink link = findActiveLink(token);

        // Consume the gateway token — validates expiry + single-use, marks as used
        PaymentToken paymentToken = paymentTokenService.consume(req.gatewayToken());

        // Load the pre-selected connector account from the token
        ProviderAccount account = providerAccountRepository.findById(paymentToken.getAccountId())
                .orElseThrow(() -> new IllegalStateException("Connector account not found"));

        String secretKey = encryptionService.decrypt(account.getEncryptedSecretKey());
        PaymentProvider provider = PaymentProvider.valueOf(paymentToken.getProvider());
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
                paymentToken.getProviderPmId(),   // resolved from gateway token — never sent by client
                idempotencyKey,
                secretKey
        ));

        savedIntent.setStatus(result.success() ? PaymentIntentStatus.SUCCEEDED : PaymentIntentStatus.FAILED);
        savedIntent.setProviderPaymentId(result.providerPaymentId());
        paymentIntentRepository.save(savedIntent);

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

        return ResponseEntity.ok(new PublicCheckoutResponse(
                result.success(),
                result.success() ? "SUCCEEDED" : "FAILED",
                savedIntent.getId(),
                result.failureCode(),
                result.failureMessage(),
                result.success() ? link.getRedirectUrl() : null
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
