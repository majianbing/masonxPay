package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.merchant.Merchant;
import com.masonx.paygateway.domain.merchant.MerchantRepository;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.service.PaymentTokenService;
import com.masonx.paygateway.service.RoutingEngine;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import com.masonx.paygateway.web.dto.CheckoutSessionResponse;
import com.masonx.paygateway.web.dto.TokenizeRequest;
import com.masonx.paygateway.web.dto.TokenizeResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Public (unauthenticated) endpoints used by the hosted checkout component.
 *
 * GET  /pub/checkout-session  — returns available provider brands + their publishable keys
 * POST /pub/tokenize          — exchanges a provider PM token for an opaque gateway token
 */
@RestController
@RequestMapping("/pub")
public class PublicCheckoutController {

    private final PaymentLinkRepository paymentLinkRepository;
    private final MerchantRepository merchantRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final CredentialsCodec credentialsCodec;
    private final RoutingEngine routingEngine;
    private final PaymentTokenService paymentTokenService;

    public PublicCheckoutController(PaymentLinkRepository paymentLinkRepository,
                                    MerchantRepository merchantRepository,
                                    ProviderAccountRepository providerAccountRepository,
                                    CredentialsCodec credentialsCodec,
                                    RoutingEngine routingEngine,
                                    PaymentTokenService paymentTokenService) {
        this.paymentLinkRepository = paymentLinkRepository;
        this.merchantRepository = merchantRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.credentialsCodec = credentialsCodec;
        this.routingEngine = routingEngine;
        this.paymentTokenService = paymentTokenService;
    }

    /**
     * Returns available provider brands + publishable keys for the checkout picker.
     *
     * Accepts any one of:
     *   - ?linkToken=xxx                    — hosted pay-link flow
     *   - Authorization: Bearer pk_xxx      — embedded SDK flow (pk identifies merchant + mode)
     *   - ?merchantId=xxx&mode=xxx          — legacy/server-side explicit params
     */
    @GetMapping("/checkout-session")
    public ResponseEntity<CheckoutSessionResponse> checkoutSession(
            @RequestParam(required = false) String linkToken,
            @RequestParam(required = false) String merchantId,
            @RequestParam(required = false, defaultValue = "TEST") String mode) {

        UUID resolvedMerchantId;
        ApiKeyMode resolvedMode;
        Long amount = null;
        String currency = null;
        String title = null;
        String description = null;

        if (linkToken != null) {
            PaymentLink link = paymentLinkRepository.findByToken(linkToken)
                    .orElseThrow(() -> new IllegalArgumentException("Payment link not found"));
            if (link.getStatus() == PaymentLinkStatus.INACTIVE || link.isExpired()) {
                throw new IllegalStateException("This payment link is no longer active");
            }
            resolvedMerchantId = link.getMerchantId();
            resolvedMode = link.getMode();
            amount = link.getAmount();
            currency = link.getCurrency().toUpperCase();
            title = link.getTitle();
            description = link.getDescription();
        } else {
            ApiKeyAuthentication apiKeyAuth = resolveApiKeyAuth();
            if (apiKeyAuth != null) {
                resolvedMerchantId = apiKeyAuth.getMerchantId();
                resolvedMode = apiKeyAuth.getMode();
            } else if (merchantId != null) {
                resolvedMerchantId = UUID.fromString(merchantId);
                resolvedMode = ApiKeyMode.valueOf(mode.toUpperCase());
            } else {
                throw new IllegalArgumentException("Provide linkToken, a publishable API key (Authorization header), or merchantId+mode");
            }
        }

        Merchant merchant = merchantRepository.findById(resolvedMerchantId).orElse(null);
        String merchantName = merchant != null ? merchant.getName() : "";

        List<PaymentProvider> brands = routingEngine.availableProviders(resolvedMerchantId, resolvedMode);

        List<CheckoutSessionResponse.ProviderOption> options = brands.stream()
                .map(provider -> {
                    ProviderAccount account = providerAccountRepository
                            .findAllByMerchantIdAndProviderAndModeAndStatus(
                                    resolvedMerchantId, provider, resolvedMode, ProviderAccountStatus.ACTIVE)
                            .stream()
                            .filter(a -> a.getEncryptedPublishableKey() != null
                                    || a.getProviderConfig() != null)
                            .findFirst()
                            .orElse(null);
                    if (account == null) return null;
                    String clientKey = credentialsCodec.clientKeyFor(account);
                    if (clientKey == null) return null;
                    return new CheckoutSessionResponse.ProviderOption(
                            provider.name(), clientKey, credentialsCodec.clientConfigFor(account));
                })
                .filter(o -> o != null)
                .toList();

        return ResponseEntity.ok(new CheckoutSessionResponse(
                merchantName, resolvedMode.name(), options, amount, currency, title, description));
    }

    /**
     * Accepts a provider PM token from the hosted component, runs weighted account selection,
     * stores a short-lived payment token, and returns an opaque gw_tok_xxx.
     */
    @PostMapping("/tokenize")
    public ResponseEntity<TokenizeResponse> tokenize(@Valid @RequestBody TokenizeRequest req) {
        UUID merchantId;
        ApiKeyMode mode;

        if (req.linkToken() != null) {
            PaymentLink link = paymentLinkRepository.findByToken(req.linkToken())
                    .orElseThrow(() -> new IllegalArgumentException("Payment link not found"));
            if (link.getStatus() == PaymentLinkStatus.INACTIVE || link.isExpired()) {
                throw new IllegalStateException("This payment link is no longer active");
            }
            merchantId = link.getMerchantId();
            mode = link.getMode();
        } else {
            ApiKeyAuthentication apiKeyAuth = resolveApiKeyAuth();
            if (apiKeyAuth != null) {
                merchantId = apiKeyAuth.getMerchantId();
                mode = apiKeyAuth.getMode();
            } else if (req.merchantId() != null && req.mode() != null) {
                merchantId = UUID.fromString(req.merchantId());
                mode = ApiKeyMode.valueOf(req.mode().toUpperCase());
            } else {
                throw new IllegalArgumentException("Provide linkToken, a publishable API key (Authorization header), or merchantId+mode");
            }
        }

        PaymentProvider provider = PaymentProvider.valueOf(req.provider().toUpperCase());
        String gatewayToken = paymentTokenService.create(merchantId, provider, mode, req.providerPmId());

        return ResponseEntity.ok(new TokenizeResponse(gatewayToken));
    }

    /** Extracts ApiKeyAuthentication from SecurityContext if present (set by ApiKeyAuthFilter). */
    private ApiKeyAuthentication resolveApiKeyAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof ApiKeyAuthentication a ? a : null;
    }
}
