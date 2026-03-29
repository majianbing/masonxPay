package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.provider.credentials.CredentialsCodec;
import com.masonx.paygateway.provider.credentials.ProviderCredentials;
import com.masonx.paygateway.web.dto.CreateProviderAccountRequest;
import com.masonx.paygateway.web.dto.ReorderConnectorsRequest;
import com.masonx.paygateway.web.dto.ProviderAccountResponse;
import com.masonx.paygateway.web.dto.UpdateProviderAccountRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ProviderAccountService {

    private final ProviderAccountRepository repo;
    private final CredentialsCodec codec;

    public ProviderAccountService(ProviderAccountRepository repo, CredentialsCodec codec) {
        this.repo = repo;
        this.codec = codec;
    }

    @Transactional(readOnly = true)
    public List<ProviderAccountResponse> list(UUID merchantId, ApiKeyMode mode) {
        return repo.findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode)
                .stream()
                .map(a -> ProviderAccountResponse.from(a, codec.clientKeyFor(a)))
                .toList();
    }

    public ProviderAccountResponse create(UUID merchantId, CreateProviderAccountRequest req) {
        PaymentProvider provider = PaymentProvider.valueOf(req.provider().toUpperCase());
        ApiKeyMode mode = ApiKeyMode.valueOf(req.mode().toUpperCase());

        if (req.primary()) {
            repo.clearPrimaryForProvider(merchantId, provider, mode);
        }

        ProviderAccount account = new ProviderAccount();
        account.setMerchantId(merchantId);
        account.setProvider(provider);
        account.setMode(mode);
        account.setLabel(req.label());
        account.setPrimary(req.primary());
        account.setWeight(req.weight() > 0 ? req.weight() : 1);

        ProviderCredentials creds = codec.fromRequest(provider, req, mode);
        codec.encode(creds, account);

        return ProviderAccountResponse.from(repo.save(account), codec.clientKeyFor(account));
    }

    public ProviderAccountResponse update(UUID merchantId, UUID accountId, UpdateProviderAccountRequest req) {
        ProviderAccount account = loadOwned(merchantId, accountId);

        if (req.label() != null && !req.label().isBlank()) {
            account.setLabel(req.label());
        }
        if (req.status() != null) {
            account.setStatus(ProviderAccountStatus.valueOf(req.status().toUpperCase()));
        }
        if (req.weight() != null && req.weight() > 0) {
            account.setWeight(req.weight());
        }

        return ProviderAccountResponse.from(repo.save(account), codec.clientKeyFor(account));
    }

    public void delete(UUID merchantId, UUID accountId) {
        repo.delete(loadOwned(merchantId, accountId));
    }

    public ProviderAccountResponse setPrimary(UUID merchantId, UUID accountId) {
        ProviderAccount account = loadOwned(merchantId, accountId);
        repo.clearPrimaryForProvider(merchantId, account.getProvider(), account.getMode());
        account.setPrimary(true);
        return ProviderAccountResponse.from(repo.save(account), codec.clientKeyFor(account));
    }

    /**
     * Loads fully-typed credentials for a specific connector account.
     * Used by charge/refund paths.
     */
    @Transactional(readOnly = true)
    public ProviderCredentials loadCredentials(UUID accountId) {
        ProviderAccount account = repo.findById(accountId)
                .orElseThrow(() -> new IllegalStateException("Connector account not found: " + accountId));
        return codec.decode(account);
    }

    /**
     * Legacy helper — resolves the primary connector key by brand.
     * Still used for flows that haven't stored connectorAccountId yet.
     */
    @Transactional(readOnly = true)
    public ProviderCredentials resolveCredentials(UUID merchantId, PaymentProvider provider, ApiKeyMode mode) {
        return repo.findByMerchantIdAndProviderAndModeAndPrimaryTrueAndStatus(
                        merchantId, provider, mode, ProviderAccountStatus.ACTIVE)
                .map(codec::decode)
                .orElseThrow(() -> new IllegalStateException(
                        "No active primary connector for " + provider + " / " + mode));
    }

    public void reorder(UUID merchantId, List<ReorderConnectorsRequest.BrandOrder> items) {
        for (ReorderConnectorsRequest.BrandOrder item : items) {
            PaymentProvider provider = PaymentProvider.valueOf(item.provider().toUpperCase());
            repo.updateDisplayOrderByMerchantIdAndProvider(merchantId, provider, item.displayOrder());
        }
    }

    private ProviderAccount loadOwned(UUID merchantId, UUID accountId) {
        return repo.findByIdAndMerchantId(accountId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found"));
    }
}
