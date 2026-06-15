package com.masonx.paygateway.redis;

import com.masonx.paygateway.metrics.PaymentMetrics;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 20)
@ConditionalOnProperty(prefix = "app.redis", name = "enabled", havingValue = "true")
public class RedisRateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final RedissonClient redissonClient;
    private final RedisHotPathProperties properties;
    private final PaymentMetrics metrics;

    public RedisRateLimitFilter(RedissonClient redissonClient,
                                RedisHotPathProperties properties,
                                PaymentMetrics metrics) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Limit limit = resolveLimit(request);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!enabled() || limit == null || !(auth instanceof ApiKeyAuthentication apiKeyAuth)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            String key = key(apiKeyAuth, limit);
            RRateLimiter rateLimiter = redissonClient.getRateLimiter(key);
            rateLimiter.trySetRate(
                    RateType.OVERALL,
                    limit.limit(),
                    properties.getRateLimit().getWindowSeconds(),
                    RateIntervalUnit.SECONDS);
            if (rateLimiter.tryAcquire()) {
                metrics.recordRedisRateLimitAllowed(limit.name());
                chain.doFilter(request, response);
                return;
            }
            metrics.recordRedisRateLimitBlocked(limit.name());
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":429,\"message\":\"Too Many Requests\"," +
                    "\"detail\":\"Merchant rate limit exceeded\"}");
        } catch (RuntimeException ex) {
            metrics.recordRedisRateLimitFallback(limit.name());
            if (properties.isFailOpen()) {
                chain.doFilter(request, response);
                return;
            }
            response.setStatus(503);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"status\":503,\"message\":\"Service Unavailable\"," +
                    "\"detail\":\"Rate limiter unavailable\"}");
        }
    }

    private boolean enabled() {
        return properties.isEnabled() && properties.getRateLimit().isEnabled();
    }

    private Limit resolveLimit(HttpServletRequest request) {
        String method = request.getMethod().toUpperCase();
        String uri = request.getRequestURI();
        if ("POST".equals(method) && "/api/v1/payment-intents".equals(uri)) {
            return new Limit("payment_intent_create",
                    properties.getRateLimit().getCreatePaymentIntentPerWindow());
        }
        if ("POST".equals(method) && PATH_MATCHER.match("/api/v1/payment-intents/*/confirm", uri)) {
            return new Limit("payment_intent_confirm",
                    properties.getRateLimit().getConfirmPaymentIntentPerWindow());
        }
        return null;
    }

    private String key(ApiKeyAuthentication auth, Limit limit) {
        return "mxp:rl:" + auth.getMerchantId() + ":" + auth.getApiKeyId() + ":" + limit.name();
    }

    private record Limit(String name, long limit) {}
}
