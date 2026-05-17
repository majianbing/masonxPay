package com.masonx.paygateway.redis;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.apikey.ApiKeyType;
import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimitFilterTest {

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RRateLimiter rateLimiter = mock(RRateLimiter.class);
    private final RedisHotPathProperties properties = new RedisHotPathProperties();
    private final PaymentMetrics metrics = new PaymentMetrics(new SimpleMeterRegistry());
    private final RedisRateLimitFilter filter = new RedisRateLimitFilter(redissonClient, properties, metrics);

    @BeforeEach
    void setUp() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        when(rateLimiter.trySetRate(any(), anyLong(), anyLong(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_allowsRequest_whenRedisLimitAllows() throws Exception {
        properties.setEnabled(true);
        authenticateApiKey();
        when(rateLimiter.tryAcquire()).thenReturn(true);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/v1/payment-intents"), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void doFilterInternal_blocksRequest_whenRedisLimitBlocks() throws Exception {
        properties.setEnabled(true);
        authenticateApiKey();
        when(rateLimiter.tryAcquire()).thenReturn(false);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/v1/payment-intents"), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void doFilterInternal_failsOpen_whenRedisUnavailableAndFailOpenEnabled() throws Exception {
        properties.setEnabled(true);
        properties.setFailOpen(true);
        authenticateApiKey();
        when(redissonClient.getRateLimiter(anyString())).thenThrow(new IllegalStateException("redis down"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/v1/payment-intents"), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void authenticateApiKey() {
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthentication(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ApiKeyMode.TEST,
                ApiKeyType.SECRET));
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }
}
