package com.masonx.paygateway.provider.credentials;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.service.EncryptionService;
import com.masonx.paygateway.web.dto.CreateProviderAccountRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Encodes credentials into ProviderAccount columns and decodes them back.
 *
 * encrypted_credentials — JSON (AES-256-GCM): secrets only (secretKey, accessToken, …)
 * provider_config       — JSON (plaintext):   public/config IDs (publishableKey, applicationId, locationId, …)
 *
 * Old connectors (encrypted_secret_key still populated) are decoded via legacy fallback.
 */
@Component
public class CredentialsCodec {

    private final EncryptionService encryption;
    private final ObjectMapper objectMapper;

    public CredentialsCodec(EncryptionService encryption, ObjectMapper objectMapper) {
        this.encryption = encryption;
        this.objectMapper = objectMapper;
    }

    // ── encode ────────────────────────────────────────────────────────────────

    public void encode(ProviderCredentials creds, ProviderAccount account) {
        switch (creds) {
            case StripeCredentials s -> {
                account.setEncryptedCredentials(
                        encryption.encrypt(toJson(Map.of("secretKey", s.secretKey()))));
                account.setProviderConfig(
                        s.publishableKey() != null && !s.publishableKey().isBlank()
                                ? toJson(Map.of("publishableKey", s.publishableKey()))
                                : null);
                account.setSecretKeyHint(hint(s.secretKey()));
            }
            case SquareCredentials sq -> {
                account.setEncryptedCredentials(
                        encryption.encrypt(toJson(Map.of("accessToken", sq.accessToken()))));
                account.setProviderConfig(toJson(Map.of(
                        "applicationId", sq.applicationId(),
                        "locationId",    sq.locationId()
                )));
                account.setSecretKeyHint(hint(sq.accessToken()));
            }
        }
    }

    /** Build a typed ProviderCredentials from an inbound create request. */
    public ProviderCredentials fromRequest(PaymentProvider provider, CreateProviderAccountRequest req,
                                           ApiKeyMode mode) {
        return switch (provider) {
            case STRIPE -> new StripeCredentials(req.secretKey(), req.publishableKey());
            case SQUARE -> new SquareCredentials(
                    req.accessToken(), req.applicationId(), req.locationId(),
                    mode == ApiKeyMode.TEST);
            default -> throw new IllegalArgumentException("Unsupported provider: " + provider);
        };
    }

    // ── decode ────────────────────────────────────────────────────────────────

    public ProviderCredentials decode(ProviderAccount account) {
        if (account.getEncryptedCredentials() != null) {
            Map<String, String> secrets = fromJson(
                    encryption.decrypt(account.getEncryptedCredentials()));
            Map<String, String> config = account.getProviderConfig() != null
                    ? fromJson(account.getProviderConfig())
                    : Map.of();
            boolean sandbox = account.getMode() == ApiKeyMode.TEST;

            return switch (account.getProvider()) {
                case STRIPE -> new StripeCredentials(
                        secrets.get("secretKey"),
                        config.get("publishableKey"));
                case SQUARE -> new SquareCredentials(
                        secrets.get("accessToken"),
                        config.get("applicationId"),
                        config.get("locationId"),
                        sandbox);
                default -> throw new IllegalStateException(
                        "No credential decoder for provider: " + account.getProvider());
            };
        }

        // Legacy fallback — old Stripe connectors created before V21
        String secretKey = account.getEncryptedSecretKey() != null
                ? encryption.decrypt(account.getEncryptedSecretKey()) : null;
        String publishableKey = account.getEncryptedPublishableKey() != null
                ? encryption.decrypt(account.getEncryptedPublishableKey()) : null;
        return new StripeCredentials(secretKey, publishableKey);
    }

    /**
     * Returns the plaintext client key for this account without decrypting secrets.
     * Used by checkout-session endpoint and ProviderAccountResponse.
     */
    public String clientKeyFor(ProviderAccount account) {
        if (account.getProviderConfig() != null) {
            Map<String, String> config = fromJson(account.getProviderConfig());
            return switch (account.getProvider()) {
                case STRIPE -> config.get("publishableKey");
                case SQUARE -> config.get("applicationId");
                default -> null;
            };
        }
        // Legacy: publishableKey was encrypted
        if (account.getEncryptedPublishableKey() != null) {
            return encryption.decrypt(account.getEncryptedPublishableKey());
        }
        return null;
    }

    public Map<String, String> clientConfigFor(ProviderAccount account) {
        if (account.getProviderConfig() == null) return Map.of();
        Map<String, String> config = fromJson(account.getProviderConfig());
        return switch (account.getProvider()) {
            case SQUARE -> {
                String loc = config.get("locationId");
                yield loc != null ? Map.of("locationId", loc) : Map.of();
            }
            default -> Map.of();
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String hint(String value) {
        if (value == null || value.length() < 4) return value;
        return value.substring(value.length() - 4);
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize credentials", e);
        }
    }

    private Map<String, String> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize credentials", e);
        }
    }
}
