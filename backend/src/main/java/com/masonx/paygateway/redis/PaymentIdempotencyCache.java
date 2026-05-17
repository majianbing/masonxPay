package com.masonx.paygateway.redis;

import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.sharding.IdempotencyReservationStatus;
import com.masonx.paygateway.sharding.PaymentIdempotencyRoute;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Component
public class PaymentIdempotencyCache {

    private static final String PREFIX = "mxp:idem:";

    private final StringRedisTemplate redisTemplate;
    private final RedisHotPathProperties properties;
    private final PaymentMetrics metrics;

    public PaymentIdempotencyCache(StringRedisTemplate redisTemplate,
                                   RedisHotPathProperties properties,
                                   PaymentMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public Optional<PaymentIdempotencyRoute> find(UUID merchantId, String idempotencyKey) {
        if (!enabled()) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(key(merchantId, idempotencyKey));
            if (value == null || value.isBlank()) {
                metrics.recordRedisIdempotencyCacheMiss();
                return Optional.empty();
            }
            String[] parts = value.split("\\|", -1);
            if (parts.length != 3) {
                metrics.recordRedisIdempotencyFallback("malformed");
                return Optional.empty();
            }
            metrics.recordRedisIdempotencyCacheHit();
            return Optional.of(new PaymentIdempotencyRoute(
                    merchantId,
                    idempotencyKey,
                    UUID.fromString(parts[0]),
                    Integer.parseInt(parts[1]),
                    IdempotencyReservationStatus.valueOf(parts[2])));
        } catch (RuntimeException ex) {
            metrics.recordRedisIdempotencyFallback("unavailable");
            return Optional.empty();
        }
    }

    public void put(PaymentIdempotencyRoute route) {
        if (!enabled() || route.status() != IdempotencyReservationStatus.COMPLETED) {
            return;
        }
        try {
            String value = route.paymentIntentId() + "|" + route.paymentShardId() + "|" + route.status().name();
            redisTemplate.opsForValue().set(
                    key(route.merchantId(), route.idempotencyKey()),
                    value,
                    Duration.ofSeconds(properties.getIdempotency().getTtlSeconds()));
        } catch (RuntimeException ex) {
            metrics.recordRedisIdempotencyFallback("unavailable");
        }
    }

    private boolean enabled() {
        return properties.isEnabled() && properties.getIdempotency().isEnabled();
    }

    private String key(UUID merchantId, String idempotencyKey) {
        return PREFIX + merchantId + ":" + sha256UrlSafe(idempotencyKey);
    }

    private static String sha256UrlSafe(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
