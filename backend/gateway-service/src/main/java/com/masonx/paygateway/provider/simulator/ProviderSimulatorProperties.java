package com.masonx.paygateway.provider.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime knobs for the Mason Simulator provider.
 * The provider is disabled by default and is intended for benchmark/preview
 * flows where we need PSP-like latency and failures without calling real PSPs.
 */
@Component
@ConfigurationProperties(prefix = "app.provider-simulator")
public class ProviderSimulatorProperties {

    private boolean enabled = false;
    private long baseLatencyMs = 25;
    private long jitterMs = 25;
    private double failureRate = 0.0;
    private double timeoutRate = 0.01;
    private long timeoutLatencyMs = 2_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getBaseLatencyMs() { return baseLatencyMs; }
    public void setBaseLatencyMs(long baseLatencyMs) { this.baseLatencyMs = Math.max(0, baseLatencyMs); }
    public long getJitterMs() { return jitterMs; }
    public void setJitterMs(long jitterMs) { this.jitterMs = Math.max(0, jitterMs); }
    public double getFailureRate() { return failureRate; }
    public void setFailureRate(double failureRate) { this.failureRate = clampRate(failureRate); }
    public double getTimeoutRate() { return timeoutRate; }
    public void setTimeoutRate(double timeoutRate) { this.timeoutRate = clampRate(timeoutRate); }
    public long getTimeoutLatencyMs() { return timeoutLatencyMs; }
    public void setTimeoutLatencyMs(long timeoutLatencyMs) { this.timeoutLatencyMs = Math.max(0, timeoutLatencyMs); }

    private double clampRate(double value) {
        if (Double.isNaN(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
