package com.masonx.paygateway.config.sentinel;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.degrade.circuitbreaker.CircuitBreakerStrategy;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.system.SystemRule;
import com.alibaba.csp.sentinel.slots.system.SystemRuleManager;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wires Alibaba Sentinel into the Spring Boot application using sentinel-core only.
 *
 * Note: sentinel-spring-webmvc-adapter 1.8.x still uses javax.servlet and is
 * incompatible with Spring Boot 3 / Jakarta EE 10. Rate limiting is handled by
 * the inner SentinelFilter (OncePerRequestFilter) which uses only jakarta.servlet.
 *
 * ── How it works ──────────────────────────────────────────────────────────────
 * SentinelFilter runs after Spring Security (auth context is already populated).
 * For each request it:
 *   1. Resolves the caller origin (merchantId or "dashboard") from the auth context
 *   2. Matches the request URI against configured flow-rule resource patterns
 *   3. Calls SphU.entry(resource) — Sentinel applies the matching FlowRule
 *   4. Passes BlockException as HTTP 429
 *
 * Resource names follow the "HTTP_METHOD:/path/pattern" convention so they match
 * the flow-rule entries in application.yml exactly.
 *
 * ── Nacos migration path ───────────────────────────────────────────────────
 * When ready to manage rules dynamically:
 *   1. Add: com.alibaba.csp:sentinel-datasource-nacos
 *   2. Replace FlowRuleManager.loadRules() in initFlowRules() with:
 *        NacosDataSource<List<FlowRule>> ds = new NacosDataSource<>(
 *            nacosAddr, group, "sentinel-flow-rules",
 *            src -> JSON.parseObject(src, new TypeReference<>() {}));
 *        FlowRuleManager.register2Property(ds.getProperty());
 *   3. Push the YAML values as a JSON array to the Nacos key.
 *      FlowRule field names are identical in both representations.
 */
@Configuration
public class SentinelConfig {

    private static final Logger log = LoggerFactory.getLogger(SentinelConfig.class);

    private static final Map<String, Integer> FLOW_GRADE = Map.of(
            "QPS",    RuleConstant.FLOW_GRADE_QPS,
            "THREAD", RuleConstant.FLOW_GRADE_THREAD
    );
    private static final Map<String, Integer> CONTROL_BEHAVIOR = Map.of(
            "REJECT",       RuleConstant.CONTROL_BEHAVIOR_DEFAULT,
            "WARM_UP",      RuleConstant.CONTROL_BEHAVIOR_WARM_UP,
            "RATE_LIMITER", RuleConstant.CONTROL_BEHAVIOR_RATE_LIMITER
    );
    private static final Map<String, Integer> DEGRADE_GRADE = Map.of(
            "SLOW_REQUEST_RATIO", CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType(),
            "ERROR_RATIO",        CircuitBreakerStrategy.ERROR_RATIO.getType(),
            "ERROR_COUNT",        CircuitBreakerStrategy.ERROR_COUNT.getType()
    );

    private final SentinelProperties props;

    public SentinelConfig(SentinelProperties props) {
        this.props = props;
    }

    // ── Rule loading ──────────────────────────────────────────────────────────

    @PostConstruct
    public void initRules() {
        initFlowRules();
        initDegradeRules();
        initSystemRule();
    }

    private void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>();
        for (SentinelProperties.FlowRuleConfig cfg : props.getFlowRules()) {
            FlowRule rule = new FlowRule(cfg.getResource());
            rule.setGrade(FLOW_GRADE.getOrDefault(cfg.getGrade().toUpperCase(),
                    RuleConstant.FLOW_GRADE_QPS));
            rule.setCount(cfg.getCount());
            rule.setControlBehavior(CONTROL_BEHAVIOR.getOrDefault(
                    cfg.getControlBehavior().toUpperCase(),
                    RuleConstant.CONTROL_BEHAVIOR_DEFAULT));
            rule.setMaxQueueingTimeMs(cfg.getMaxQueueingTimeMs());
            rule.setWarmUpPeriodSec(cfg.getWarmUpPeriodSec());
            rule.setLimitApp(cfg.getLimitApp());
            rules.add(rule);
        }
        FlowRuleManager.loadRules(rules);
        log.info("Sentinel: loaded {} flow rule(s)", rules.size());
    }

    private void initDegradeRules() {
        List<DegradeRule> rules = new ArrayList<>();
        for (SentinelProperties.DegradeRuleConfig cfg : props.getDegradeRules()) {
            DegradeRule rule = new DegradeRule(cfg.getResource());
            rule.setGrade(DEGRADE_GRADE.getOrDefault(cfg.getGrade().toUpperCase(),
                    CircuitBreakerStrategy.SLOW_REQUEST_RATIO.getType()));
            rule.setCount(cfg.getCount());
            rule.setSlowRatioThreshold(cfg.getSlowRatioThreshold());
            rule.setMinRequestAmount(cfg.getMinRequestAmount());
            rule.setStatIntervalMs(cfg.getStatIntervalMs());
            rule.setTimeWindow(cfg.getTimeWindow());
            rules.add(rule);
        }
        DegradeRuleManager.loadRules(rules);
        log.info("Sentinel: loaded {} degrade rule(s)", rules.size());
    }

    private void initSystemRule() {
        SentinelProperties.SystemConfig sys = props.getSystem();
        if (sys.getHighestSystemLoad() < 0 && sys.getHighestCpuUsage() < 0
                && sys.getAvgRt() < 0 && sys.getMaxThread() < 0) {
            return;
        }
        SystemRule rule = new SystemRule();
        rule.setHighestSystemLoad(sys.getHighestSystemLoad());
        rule.setHighestCpuUsage(sys.getHighestCpuUsage());
        rule.setAvgRt(sys.getAvgRt());
        rule.setMaxThread(sys.getMaxThread());
        SystemRuleManager.loadRules(List.of(rule));
        log.info("Sentinel: system rule loaded (load={}, cpu={})",
                sys.getHighestSystemLoad(), sys.getHighestCpuUsage());
    }

    // ── Request filter ────────────────────────────────────────────────────────

    /**
     * Runs after Spring Security so the auth context is populated.
     * Combines origin resolution and rate-limit enforcement in one pass.
     */
    @Component
    @Order(Ordered.LOWEST_PRECEDENCE - 10)
    public static class SentinelFilter extends OncePerRequestFilter {

        private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
        private final SentinelProperties props;

        public SentinelFilter(SentinelProperties props) {
            this.props = props;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String origin   = resolveOrigin();
            String resource = resolveResource(request);

            // No flow rule matches this path — pass through without Sentinel overhead
            if (resource == null) {
                chain.doFilter(request, response);
                return;
            }

            ContextUtil.enter(resource, origin);
            Entry entry = null;
            try {
                entry = SphU.entry(resource, EntryType.IN);
                chain.doFilter(request, response);
            } catch (BlockException e) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"status\":429,\"message\":\"Too Many Requests\"," +
                        "\"detail\":\"Rate limit exceeded — slow down and retry\"}");
            } finally {
                if (entry != null) entry.exit();
                ContextUtil.exit();
            }
        }

        /** Caller identity: merchantId for API-key requests, "dashboard" for JWT users. */
        private String resolveOrigin() {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth instanceof ApiKeyAuthentication ak) {
                return ak.getMerchantId().toString();
            }
            return (auth != null && auth.isAuthenticated()) ? "dashboard" : "unknown";
        }

        /**
         * Matches the incoming request against configured flow-rule resource patterns.
         * Pattern format: "HTTP_METHOD:/path/with/{optional}/wildcards"
         * Returns the matching resource name, or null if no rule covers this path.
         */
        private String resolveResource(HttpServletRequest request) {
            String method = request.getMethod().toUpperCase();
            String uri    = request.getRequestURI();
            for (SentinelProperties.FlowRuleConfig rule : props.getFlowRules()) {
                String res = rule.getResource();
                int colon  = res.indexOf(':');
                if (colon < 0) continue;
                String ruleMethod = res.substring(0, colon).toUpperCase();
                String rulePath   = res.substring(colon + 1);
                if (ruleMethod.equals(method) && PATH_MATCHER.match(rulePath, uri)) {
                    return res;
                }
            }
            return null;
        }
    }
}
