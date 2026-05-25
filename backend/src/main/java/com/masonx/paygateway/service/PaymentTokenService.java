package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.instrument.InstrumentPortability;
import com.masonx.paygateway.domain.instrument.InstrumentSource;
import com.masonx.paygateway.domain.instrument.InstrumentType;
import com.masonx.paygateway.domain.instrument.PaymentInstrument;
import com.masonx.paygateway.domain.instrument.PaymentInstrumentRepository;
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
    private final PaymentInstrumentRepository instrumentRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final RoutingEngine routingEngine;

    public PaymentTokenService(PaymentTokenRepository repo,
                               PaymentInstrumentRepository instrumentRepository,
                               ProviderAccountRepository providerAccountRepository,
                               RoutingEngine routingEngine) {
        this.repo = repo;
        this.instrumentRepository = instrumentRepository;
        this.providerAccountRepository = providerAccountRepository;
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
        token.setInstrumentId(createProviderScopedInstrument(merchantId, account, providerPmId).getId());
        token.setExpiresAt(Instant.now().plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES));

        PaymentToken saved = repo.save(token);
        return "gw_tok_" + saved.getId().toString().replace("-", "");
    }

    /**
     * Creates a payment token pinned to a specific connector account, bypassing routing.
     * Used by the preview flow where the merchant selects an exact account to test.
     */
    public String createForAccount(UUID merchantId, UUID accountId, PaymentProvider provider,
                                   ApiKeyMode mode, String providerPmId) {
        ProviderAccount account = providerAccountRepository.findByIdAndMerchantId(accountId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Connector account not found"));
        if (account.getProvider() != provider || account.getMode() != mode) {
            throw new IllegalArgumentException("Connector account does not match provider or mode");
        }

        PaymentToken token = new PaymentToken();
        token.setMerchantId(merchantId);
        token.setProvider(provider.name());
        token.setAccountId(accountId);
        token.setProviderPmId(providerPmId);
        token.setInstrumentId(createProviderScopedInstrument(merchantId, account, providerPmId).getId());
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

    private PaymentInstrument createProviderScopedInstrument(UUID merchantId, ProviderAccount account, String providerPmId) {
        PaymentInstrument instrument = new PaymentInstrument();
        instrument.setMerchantId(merchantId);
        instrument.setType(InstrumentType.CARD);
        instrument.setSource(InstrumentSource.PROVIDER_TOKEN);
        instrument.setPortability(InstrumentPortability.PROVIDER_SCOPED);
        instrument.setProvider(account.getProvider());
        instrument.setProviderAccountId(account.getId());
        instrument.setTokenReference(providerPmId != null && !providerPmId.isBlank()
                ? providerPmId
                : "provider_pending_" + UUID.randomUUID());
        return instrumentRepository.save(instrument);
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
