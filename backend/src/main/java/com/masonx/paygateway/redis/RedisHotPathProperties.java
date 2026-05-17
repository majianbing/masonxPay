package com.masonx.paygateway.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.redis")
public class RedisHotPathProperties {

    private boolean enabled = false;
    private boolean failOpen = true;
    private final Idempotency idempotency = new Idempotency();
    private final RateLimit rateLimit = new RateLimit();
    private final ProviderHealth providerHealth = new ProviderHealth();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    public Idempotency getIdempotency() { return idempotency; }
    public RateLimit getRateLimit() { return rateLimit; }
    public ProviderHealth getProviderHealth() { return providerHealth; }

    public static class Idempotency {
        private boolean enabled = true;
        private long ttlSeconds = 86_400;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }

    public static class RateLimit {
        private boolean enabled = true;
        private long windowSeconds = 60;
        private long createPaymentIntentPerWindow = 600;
        private long confirmPaymentIntentPerWindow = 300;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(long windowSeconds) { this.windowSeconds = windowSeconds; }
        public long getCreatePaymentIntentPerWindow() { return createPaymentIntentPerWindow; }
        public void setCreatePaymentIntentPerWindow(long createPaymentIntentPerWindow) {
            this.createPaymentIntentPerWindow = createPaymentIntentPerWindow;
        }
        public long getConfirmPaymentIntentPerWindow() { return confirmPaymentIntentPerWindow; }
        public void setConfirmPaymentIntentPerWindow(long confirmPaymentIntentPerWindow) {
            this.confirmPaymentIntentPerWindow = confirmPaymentIntentPerWindow;
        }
    }

    public static class ProviderHealth {
        private boolean enabled = true;
        private long ttlSeconds = 600;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    }
}
