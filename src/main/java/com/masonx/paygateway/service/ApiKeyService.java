package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.*;
import com.masonx.paygateway.web.dto.ApiKeyPairResponse;
import com.masonx.paygateway.web.dto.ApiKeyResponse;
import com.masonx.paygateway.web.dto.CreateApiKeyRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Creates a sk+pk pair atomically.
     * - sk: hashed only; raw value returned once in secretKey.secretPlaintext, never again.
     * - pk: stored in plaintext (public identifier); always readable via publishableKey.plaintextKey.
     */
    public ApiKeyPairResponse create(UUID merchantId, CreateApiKeyRequest req) {
        ApiKeyMode mode = ApiKeyMode.valueOf(req.mode() != null ? req.mode().toUpperCase() : "TEST");

        // Secret key
        String skPrefix = "sk_" + mode.name().toLowerCase() + "_";
        String skRaw    = skPrefix + generateRandomHex(24);
        ApiKey sk = new ApiKey();
        sk.setMerchantId(merchantId);
        sk.setMode(mode);
        sk.setType(ApiKeyType.SECRET);
        sk.setKeyHash(sha256(skRaw));
        sk.setPrefix(skPrefix);
        sk.setName(req.name());
        // plaintextKey intentionally null — sk is never stored in plaintext
        sk = apiKeyRepository.save(sk);

        // Publishable key (pk) — stored in plaintext, safe to show anytime
        String pkPrefix = "pk_" + mode.name().toLowerCase() + "_";
        String pkRaw    = pkPrefix + generateRandomHex(24);
        ApiKey pk = new ApiKey();
        pk.setMerchantId(merchantId);
        pk.setMode(mode);
        pk.setType(ApiKeyType.PUBLISHABLE);
        pk.setKeyHash(sha256(pkRaw));
        pk.setPrefix(pkPrefix);
        pk.setName(req.name());
        pk.setPlaintextKey(pkRaw);   // safe to persist — pk is a public identifier
        pk = apiKeyRepository.save(pk);

        return new ApiKeyPairResponse(
                ApiKeyResponse.from(sk, skRaw),   // skRaw returned once only
                ApiKeyResponse.from(pk, null)      // pk plaintext is on the entity, always available
        );
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(UUID merchantId) {
        return apiKeyRepository.findAllByMerchantId(merchantId)
                .stream()
                .map(k -> ApiKeyResponse.from(k, null))
                .toList();
    }

    /**
     * Revoking the sk also revokes the paired pk (same name + mode).
     * Revoking the pk alone revokes only the pk.
     */
    public void revoke(UUID merchantId, UUID keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new IllegalArgumentException("API key not found"));
        if (!key.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("API key does not belong to this merchant");
        }
        if (key.getStatus() == ApiKeyStatus.REVOKED) {
            throw new IllegalStateException("API key is already revoked");
        }

        key.setStatus(ApiKeyStatus.REVOKED);
        key.setRevokedAt(Instant.now());
        apiKeyRepository.save(key);

        // If revoking a sk, also revoke the paired pk (same name + mode)
        if (key.getType() == ApiKeyType.SECRET) {
            apiKeyRepository.findAllByMerchantId(merchantId).stream()
                    .filter(k -> k.getType() == ApiKeyType.PUBLISHABLE
                            && k.getMode() == key.getMode()
                            && k.getStatus() == ApiKeyStatus.ACTIVE
                            && key.getName() != null && key.getName().equals(k.getName()))
                    .forEach(pk -> {
                        pk.setStatus(ApiKeyStatus.REVOKED);
                        pk.setRevokedAt(Instant.now());
                        apiKeyRepository.save(pk);
                    });
        }
    }

    private String buildPrefix(ApiKeyType type, ApiKeyMode mode) {
        String t = type == ApiKeyType.SECRET ? "sk" : "pk";
        String m = mode == ApiKeyMode.TEST ? "test" : "live";
        return t + "_" + m + "_";
    }

    private String generateRandomHex(int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
