package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.payment.PaymentToken;
import com.masonx.paygateway.domain.payment.PaymentTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Transactional
public class PaymentTokenService {

    private static final int TOKEN_TTL_MINUTES = 15;

    private final PaymentTokenRepository repo;
    private final RoutingEngine routingEngine;

    public PaymentTokenService(PaymentTokenRepository repo, RoutingEngine routingEngine) {
        this.repo = repo;
        this.routingEngine = routingEngine;
    }

    /**
     * Selects an account via weighted-random routing, then stores a short-lived
     * payment token mapping gw_tok_xxx → provider PM ID.
     *
     * @param merchantId   merchant performing the payment
     * @param provider     provider brand the customer chose in the picker
     * @param mode         TEST or LIVE
     * @param providerPmId raw PM token from provider JS SDK (e.g. pm_3P... from Stripe.js)
     * @return opaque gateway token string ("gw_tok_" + UUID hex)
     */
    public String create(UUID merchantId, PaymentProvider provider, ApiKeyMode mode, String providerPmId) {
        ProviderAccount account = routingEngine.resolveAccountForProvider(merchantId, provider, mode)
                .orElseThrow(() -> new IllegalStateException(
                        "No active connector found for provider " + provider + " in " + mode + " mode"));

        PaymentToken token = new PaymentToken();
        token.setMerchantId(merchantId);
        token.setProvider(provider.name());
        token.setAccountId(account.getId());
        token.setProviderPmId(providerPmId);
        token.setExpiresAt(Instant.now().plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));

        PaymentToken saved = repo.save(token);
        return "gw_tok_" + saved.getId().toString().replace("-", "");
    }

    /**
     * Resolves and consumes a gateway token. Validates expiry and single-use.
     * Marks the token as used atomically.
     */
    public PaymentToken consume(String gatewayToken) {
        UUID id = parseTokenId(gatewayToken);

        PaymentToken token = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid payment token"));

        if (token.isExpired()) {
            throw new IllegalStateException("Payment token has expired");
        }
        if (token.isUsed()) {
            throw new IllegalStateException("Payment token has already been used");
        }

        token.setUsedAt(Instant.now());
        return repo.save(token);
    }

    /** Peeks at a token without consuming it — for checkout-session info lookups. */
    @Transactional(readOnly = true)
    public PaymentToken peek(String gatewayToken) {
        UUID id = parseTokenId(gatewayToken);
        PaymentToken token = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Invalid payment token"));
        if (token.isExpired() || token.isUsed()) {
            throw new IllegalStateException("Payment token is no longer valid");
        }
        return token;
    }

    private UUID parseTokenId(String gatewayToken) {
        if (gatewayToken == null || !gatewayToken.startsWith("gw_tok_")) {
            throw new IllegalArgumentException("Invalid payment token format");
        }
        String hex = gatewayToken.substring("gw_tok_".length());
        // Re-insert hyphens: 8-4-4-4-12
        String uuidStr = hex.substring(0, 8) + "-"
                + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-"
                + hex.substring(16, 20) + "-"
                + hex.substring(20);
        return UUID.fromString(uuidStr);
    }
}
