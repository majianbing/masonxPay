package com.masonx.paygateway.sharding;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Component
public class PaymentShardRouter {

    private final PaymentShardingProperties properties;

    public PaymentShardRouter(PaymentShardingProperties properties) {
        this.properties = properties;
    }

    public int shardForPaymentId(UUID paymentId) {
        return Math.floorMod(Objects.requireNonNull(paymentId, "paymentId").hashCode(), properties.getShardCount());
    }

    public int shardForIdempotencyKey(UUID merchantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        return shardFor("idem:" + Objects.requireNonNull(merchantId, "merchantId") + ":" + idempotencyKey);
    }

    public int shardForProviderPaymentRef(String provider, UUID connectorAccountId, String providerPaymentId) {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            throw new IllegalArgumentException("providerPaymentId must not be blank");
        }
        String normalizedProvider = provider.trim().toUpperCase(Locale.ROOT);
        return shardFor("provider-ref:" + normalizedProvider + ":"
                + Objects.requireNonNull(connectorAccountId, "connectorAccountId") + ":"
                + providerPaymentId);
    }

    public String paymentIntentsTable(UUID paymentId) {
        return "payment_intents_" + suffixForShard(shardForPaymentId(paymentId));
    }

    public String paymentRequestsTable(UUID paymentId) {
        return "payment_requests_" + suffixForShard(shardForPaymentId(paymentId));
    }

    public String idempotencyKeysTable(UUID merchantId, String idempotencyKey) {
        return "payment_idempotency_keys_" + suffixForShard(shardForIdempotencyKey(merchantId, idempotencyKey));
    }

    public String providerPaymentRefsTable(String provider, UUID connectorAccountId, String providerPaymentId) {
        return "provider_payment_refs_" + suffixForShard(
                shardForProviderPaymentRef(provider, connectorAccountId, providerPaymentId));
    }

    public String suffixForShard(int shard) {
        int shardCount = properties.getShardCount();
        if (shard < 0 || shard >= shardCount) {
            throw new IllegalArgumentException("shard must be between 0 and " + (shardCount - 1));
        }
        int width = String.valueOf(shardCount - 1).length();
        return String.format(Locale.ROOT, "%0" + width + "d", shard);
    }

    private int shardFor(String key) {
        byte[] digest = sha256(key);
        int hash = ByteBuffer.wrap(digest, 0, Integer.BYTES).getInt();
        return Math.floorMod(hash, properties.getShardCount());
    }

    private static byte[] sha256(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
