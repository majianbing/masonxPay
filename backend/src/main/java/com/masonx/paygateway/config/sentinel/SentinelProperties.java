package com.masonx.paygateway.config.sentinel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel rule configuration loaded from application.yml (app.sentinel.*).
 *
 * Mirrors Sentinel's own rule field names so migrating to Nacos later is trivial:
 *   1. Add sentinel-datasource-nacos dependency
 *   2. Push the same values as JSON to a Nacos config key
 *   3. Replace the @PostConstruct loader with a NacosDataSource binding
 *   — the rule model itself does not change.
 *
 * Grade / controlBehavior use human-readable strings here; SentinelConfig maps
 * them to Sentinel's integer constants at startup.
 */
@Component
@ConfigurationProperties(prefix = "app.sentinel")
public class SentinelProperties {

    private List<FlowRuleConfig> flowRules = new ArrayList<>();
    private List<DegradeRuleConfig> degradeRules = new ArrayList<>();
    private SystemConfig system = new SystemConfig();

    // ── Flow rules ────────────────────────────────────────────────────────────

    public static class FlowRuleConfig {
        /** Resource name — matches the pattern "HTTP_METHOD:URI" used by the WebMVC adapter. */
        private String resource;

        /** QPS (default) | THREAD */
        private String grade = "QPS";

        /** Request count/QPS threshold. */
        private double count;

        /**
         * REJECT        — immediately reject requests over the limit (default)
         * WARM_UP       — gradually ramp up from count/3 to count over warmUpPeriodSec
         * RATE_LIMITER  — leaky bucket, queues requests at a steady pace (recommended for payment APIs)
         */
        private String controlBehavior = "REJECT";

        /** Max time (ms) a request may wait in the queue. Only used with RATE_LIMITER. */
        private int maxQueueingTimeMs = 500;

        /** Warm-up ramp duration in seconds. Only used with WARM_UP. */
        private int warmUpPeriodSec = 10;

        /**
         * Origin (caller) this rule applies to.
         * "default" applies to every caller.
         * Set to a specific merchantId to give that merchant a dedicated limit.
         */
        private String limitApp = "default";

        public String getResource() { return resource; }
        public void setResource(String resource) { this.resource = resource; }
        public String getGrade() { return grade; }
        public void setGrade(String grade) { this.grade = grade; }
        public double getCount() { return count; }
        public void setCount(double count) { this.count = count; }
        public String getControlBehavior() { return controlBehavior; }
        public void setControlBehavior(String controlBehavior) { this.controlBehavior = controlBehavior; }
        public int getMaxQueueingTimeMs() { return maxQueueingTimeMs; }
        public void setMaxQueueingTimeMs(int maxQueueingTimeMs) { this.maxQueueingTimeMs = maxQueueingTimeMs; }
        public int getWarmUpPeriodSec() { return warmUpPeriodSec; }
        public void setWarmUpPeriodSec(int warmUpPeriodSec) { this.warmUpPeriodSec = warmUpPeriodSec; }
        public String getLimitApp() { return limitApp; }
        public void setLimitApp(String limitApp) { this.limitApp = limitApp; }
    }

    // ── Degrade (circuit breaker) rules ───────────────────────────────────────

    public static class DegradeRuleConfig {
        /** Resource name — typically the provider call label e.g. "stripe-charge". */
        private String resource;

        /** SLOW_REQUEST_RATIO | ERROR_RATIO | ERROR_COUNT */
        private String grade = "SLOW_REQUEST_RATIO";

        /**
         * Threshold value — interpretation depends on grade:
         *   SLOW_REQUEST_RATIO : response-time threshold in ms that marks a request as "slow"
         *   ERROR_RATIO        : error ratio threshold (0.0–1.0)
         *   ERROR_COUNT        : absolute error count
         */
        private double count;

        /** Ratio of slow requests that triggers the circuit (0.0–1.0). Used with SLOW_REQUEST_RATIO. */
        private double slowRatioThreshold = 0.5;

        /** Minimum number of requests in the stat window before the rule can trigger. */
        private int minRequestAmount = 5;

        /** Sliding statistics window size in milliseconds. */
        private int statIntervalMs = 10_000;

        /** Circuit open duration in seconds before switching to half-open for a probe. */
        private int timeWindow = 30;

        public String getResource() { return resource; }
        public void setResource(String resource) { this.resource = resource; }
        public String getGrade() { return grade; }
        public void setGrade(String grade) { this.grade = grade; }
        public double getCount() { return count; }
        public void setCount(double count) { this.count = count; }
        public double getSlowRatioThreshold() { return slowRatioThreshold; }
        public void setSlowRatioThreshold(double slowRatioThreshold) { this.slowRatioThreshold = slowRatioThreshold; }
        public int getMinRequestAmount() { return minRequestAmount; }
        public void setMinRequestAmount(int minRequestAmount) { this.minRequestAmount = minRequestAmount; }
        public int getStatIntervalMs() { return statIntervalMs; }
        public void setStatIntervalMs(int statIntervalMs) { this.statIntervalMs = statIntervalMs; }
        public int getTimeWindow() { return timeWindow; }
        public void setTimeWindow(int timeWindow) { this.timeWindow = timeWindow; }
    }

    // ── System protection rule ────────────────────────────────────────────────

    public static class SystemConfig {
        /** Reject all new requests when system 1-min load average exceeds this. -1 = disabled. */
        private double highestSystemLoad = -1;

        /** Reject all new requests when CPU usage exceeds this (0.0–1.0). -1 = disabled. */
        private double highestCpuUsage = -1;

        /** Max average response time (ms) across all resources. -1 = disabled. */
        private long avgRt = -1;

        /** Max total concurrent threads. -1 = disabled. */
        private long maxThread = -1;

        public double getHighestSystemLoad() { return highestSystemLoad; }
        public void setHighestSystemLoad(double highestSystemLoad) { this.highestSystemLoad = highestSystemLoad; }
        public double getHighestCpuUsage() { return highestCpuUsage; }
        public void setHighestCpuUsage(double highestCpuUsage) { this.highestCpuUsage = highestCpuUsage; }
        public long getAvgRt() { return avgRt; }
        public void setAvgRt(long avgRt) { this.avgRt = avgRt; }
        public long getMaxThread() { return maxThread; }
        public void setMaxThread(long maxThread) { this.maxThread = maxThread; }
    }

    public List<FlowRuleConfig> getFlowRules() { return flowRules; }
    public void setFlowRules(List<FlowRuleConfig> flowRules) { this.flowRules = flowRules; }
    public List<DegradeRuleConfig> getDegradeRules() { return degradeRules; }
    public void setDegradeRules(List<DegradeRuleConfig> degradeRules) { this.degradeRules = degradeRules; }
    public SystemConfig getSystem() { return system; }
    public void setSystem(SystemConfig system) { this.system = system; }
}
