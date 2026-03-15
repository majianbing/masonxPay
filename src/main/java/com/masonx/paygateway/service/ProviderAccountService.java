package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.web.dto.CreateProviderAccountRequest;
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
    private final EncryptionService encryption;

    public ProviderAccountService(ProviderAccountRepository repo, EncryptionService encryption) {
        this.repo = repo;
        this.encryption = encryption;
    }

    @Transactional(readOnly = true)
    public List<ProviderAccountResponse> list(UUID merchantId, ApiKeyMode mode) {
        return repo.findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode)
                .stream()
                .map(ProviderAccountResponse::from)
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
        account.setEncryptedSecretKey(encryption.encrypt(req.secretKey()));
        account.setSecretKeyHint(buildHint(req.secretKey()));
        account.setPrimary(req.primary());

        if (req.publishableKey() != null && !req.publishableKey().isBlank()) {
            account.setEncryptedPublishableKey(encryption.encrypt(req.publishableKey()));
        }

        return ProviderAccountResponse.from(repo.save(account));
    }

    public ProviderAccountResponse update(UUID merchantId, UUID accountId, UpdateProviderAccountRequest req) {
        ProviderAccount account = loadOwned(merchantId, accountId);

        if (req.label() != null && !req.label().isBlank()) {
            account.setLabel(req.label());
        }
        if (req.status() != null) {
            account.setStatus(ProviderAccountStatus.valueOf(req.status().toUpperCase()));
        }

        return ProviderAccountResponse.from(repo.save(account));
    }

    public void delete(UUID merchantId, UUID accountId) {
        ProviderAccount account = loadOwned(merchantId, accountId);
        repo.delete(account);
    }

    public ProviderAccountResponse setPrimary(UUID merchantId, UUID accountId) {
        ProviderAccount account = loadOwned(merchantId, accountId);
        repo.clearPrimaryForProvider(merchantId, account.getProvider(), account.getMode());
        account.setPrimary(true);
        return ProviderAccountResponse.from(repo.save(account));
    }

    /**
     * Decrypts and returns the secret key for use in provider API calls.
     * Called internally only — never returned in HTTP responses.
     */
    @Transactional(readOnly = true)
    public String resolveSecretKey(UUID merchantId, PaymentProvider provider, ApiKeyMode mode) {
        return repo.findByMerchantIdAndProviderAndModeAndPrimaryTrueAndStatus(
                        merchantId, provider, mode, ProviderAccountStatus.ACTIVE)
                .map(a -> encryption.decrypt(a.getEncryptedSecretKey()))
                .orElse(null);
    }

    private ProviderAccount loadOwned(UUID merchantId, UUID accountId) {
        return repo.findByIdAndMerchantId(accountId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Connector not found"));
    }

    private String buildHint(String key) {
        if (key == null || key.length() < 4) return key;
        return key.substring(key.length() - 4);
    }
}
