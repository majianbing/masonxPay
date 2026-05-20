package com.masonx.paygateway.redis;

import com.masonx.paygateway.metrics.PaymentMetrics;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.OptionalDouble;
import java.util.UUID;

@Component
public class RedisProviderHealthCache {

    private static final String PREFIX = "mxp:provider-health:";

    private final StringRedisTemplate redisTemplate;
    private final RedisHotPathProperties properties;
    private final PaymentMetrics metrics;

    public RedisProviderHealthCache(StringRedisTemplate redisTemplate,
                                    RedisHotPathProperties properties,
                                    PaymentMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public OptionalDouble get(UUID accountId) {
        if (!enabled()) {
            return OptionalDouble.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(key(accountId));
            if (value == null || value.isBlank()) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(Double.parseDouble(value));
        } catch (RuntimeException ex) {
            metrics.recordRedisProviderHealthFallback("read");
            return OptionalDouble.empty();
        }
    }

    public void put(UUID accountId, double successRate) {
        if (!enabled()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(accountId),
                    Double.toString(successRate),
                    Duration.ofSeconds(properties.getProviderHealth().getTtlSeconds()));
        } catch (RuntimeException ex) {
            metrics.recordRedisProviderHealthFallback("write");
        }
    }

    private boolean enabled() {
        return properties.isEnabled() && properties.getProviderHealth().isEnabled();
    }

    private String key(UUID accountId) {
        return PREFIX + accountId;
    }
}
