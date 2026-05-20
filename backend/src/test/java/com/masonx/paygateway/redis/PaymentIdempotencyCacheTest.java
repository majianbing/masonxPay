package com.masonx.paygateway.redis;

import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.sharding.IdempotencyReservationStatus;
import com.masonx.paygateway.sharding.PaymentIdempotencyRoute;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentIdempotencyCacheTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final RedisHotPathProperties properties = new RedisHotPathProperties();
    private final PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
    private final PaymentIdempotencyCache cache = new PaymentIdempotencyCache(redisTemplate, properties, metrics);

    @Test
    void find_returnsRoute_whenCacheContainsCompletedRoute() {
        properties.setEnabled(true);
        UUID merchantId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(intentId + "|7|COMPLETED");

        Optional<PaymentIdempotencyRoute> route = cache.find(merchantId, "idem-1");

        assertThat(route).isPresent();
        assertThat(route.get().merchantId()).isEqualTo(merchantId);
        assertThat(route.get().idempotencyKey()).isEqualTo("idem-1");
        assertThat(route.get().paymentIntentId()).isEqualTo(intentId);
        assertThat(route.get().paymentShardId()).isEqualTo(7);
        assertThat(route.get().status()).isEqualTo(IdempotencyReservationStatus.COMPLETED);
    }

    @Test
    void find_fallsBackToDatabasePath_whenRedisFails() {
        properties.setEnabled(true);
        when(redisTemplate.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        Optional<PaymentIdempotencyRoute> route = cache.find(UUID.randomUUID(), "idem-1");

        assertThat(route).isEmpty();
    }
}
