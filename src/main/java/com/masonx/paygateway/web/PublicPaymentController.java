package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.provider.ChargeRequest;
import com.masonx.paygateway.provider.ChargeResult;
import com.masonx.paygateway.provider.PaymentProviderDispatcher;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
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
    private final CredentialsCodec credentialsCodec;
    private final PaymentProviderDispatcher dispatcher;
    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final PaymentTokenService paymentTokenService;

    public PublicPaymentController(PaymentLinkRepository paymentLinkRepository,
                                   ProviderAccountRepository providerAccountRepository,
                                   CredentialsCodec credentialsCodec,
                                   PaymentProviderDispatcher dispatcher,
                                   PaymentIntentRepository paymentIntentRepository,
                                   PaymentRequestRepository paymentRequestRepository,
                                   PaymentTokenService paymentTokenService) {
        this.paymentLinkRepository = paymentLinkRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.credentialsCodec = credentialsCodec;
        this.dispatcher = dispatcher;
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.paymentTokenService = paymentTokenService;
    }

    @PostMapping("/{token}/checkout")
    public ResponseEntity<PublicCheckoutResponse> checkout(
            @PathVariable String token,
            @Valid @RequestBody PublicCheckoutRequest req) {

        PaymentLink link = findActiveLink(token);

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
