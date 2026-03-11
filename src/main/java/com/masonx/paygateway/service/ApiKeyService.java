package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.*;
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

    public ApiKeyResponse create(UUID merchantId, CreateApiKeyRequest req) {
        // MVP: test mode only
        ApiKeyMode mode = ApiKeyMode.TEST;
        String prefix = buildPrefix(req.type(), mode);
        String rawKey = prefix + generateRandomHex(24);
        String hash = sha256(rawKey);

        ApiKey key = new ApiKey();
        key.setMerchantId(merchantId);
        key.setMode(mode);
        key.setType(req.type());
        key.setKeyHash(hash);
        key.setPrefix(prefix);
        key.setName(req.name());

        key = apiKeyRepository.save(key);
        return ApiKeyResponse.from(key, rawKey);  // plaintext returned only here
    }

    @Transactional(readOnly = true)
    public List<ApiKeyResponse> list(UUID merchantId) {
        return apiKeyRepository.findAllByMerchantId(merchantId)
                .stream()
                .map(k -> ApiKeyResponse.from(k, null))
                .toList();
    }

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
