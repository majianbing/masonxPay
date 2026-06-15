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

    /**
     * How per-call latency is sampled.
     *   UNIFORM   — legacy model: baseLatencyMs + uniform[0, jitterMs].
     *   LOGNORMAL — right-skewed model fitted to latencyP50Ms / latencyP99Ms,
     *               for a realistic PSP tail in capacity benchmarks.
     */
    public enum LatencyModel { UNIFORM, LOGNORMAL }

    private boolean enabled = false;
    private LatencyModel latencyModel = LatencyModel.UNIFORM;

    // ── UNIFORM model ─────────────────────────────────────────────────────────
    private long baseLatencyMs = 25;
    private long jitterMs = 25;

    // ── LOGNORMAL model ───────────────────────────────────────────────────────
    // Fitted from these two percentiles; see MasonSimulatorPaymentProviderService.
    private long latencyP50Ms = 120;
    private long latencyP99Ms = 380;
    /** Hard cap so the log-normal tail cannot produce pathological multi-second stalls. */
    private long maxLatencyMs = 5_000;

    // ── Faults ────────────────────────────────────────────────────────────────
    private double failureRate = 3.1;
    private double timeoutRate = 0.007;
    private long timeoutLatencyMs = 2_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public LatencyModel getLatencyModel() { return latencyModel; }
    public void setLatencyModel(LatencyModel latencyModel) {
        this.latencyModel = latencyModel != null ? latencyModel : LatencyModel.UNIFORM;
    }
    public long getBaseLatencyMs() { return baseLatencyMs; }
    public void setBaseLatencyMs(long baseLatencyMs) { this.baseLatencyMs = Math.max(0, baseLatencyMs); }
    public long getJitterMs() { return jitterMs; }
    public void setJitterMs(long jitterMs) { this.jitterMs = Math.max(0, jitterMs); }
    public long getLatencyP50Ms() { return latencyP50Ms; }
    public void setLatencyP50Ms(long latencyP50Ms) { this.latencyP50Ms = Math.max(1, latencyP50Ms); }
    public long getLatencyP99Ms() { return latencyP99Ms; }
    public void setLatencyP99Ms(long latencyP99Ms) { this.latencyP99Ms = Math.max(1, latencyP99Ms); }
    public long getMaxLatencyMs() { return maxLatencyMs; }
    public void setMaxLatencyMs(long maxLatencyMs) { this.maxLatencyMs = Math.max(1, maxLatencyMs); }
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
