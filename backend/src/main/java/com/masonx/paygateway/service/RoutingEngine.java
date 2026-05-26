package com.masonx.paygateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.routing.RoutePolicy;
import com.masonx.paygateway.domain.routing.RoutePolicyRepository;
import com.masonx.paygateway.domain.routing.RoutePolicyRoute;
import com.masonx.paygateway.domain.routing.RoutePolicyRouteRepository;
import com.masonx.paygateway.domain.routing.RoutePolicyStatus;
import com.masonx.paygateway.domain.routing.RoutePolicyStep;
import com.masonx.paygateway.domain.routing.RoutePolicyStepRepository;
import com.masonx.paygateway.domain.routing.RoutingRule;
import com.masonx.paygateway.domain.routing.RoutingRuleRepository;
import com.masonx.paygateway.health.ConnectorHealthService;
import com.masonx.paygateway.service.routing.ProviderAccountCapabilityService;
import com.masonx.paygateway.service.routing.RoutingContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);

    /**
     * Connectors whose rolling 30-min success rate drops below this threshold are
     * treated as "degraded" — routing prefers healthy connectors but will still use
     * a degraded one if it's the only option.
     */
    static final double SUCCESS_RATE_THRESHOLD = 0.80;

    private final RoutingRuleRepository     routingRuleRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final ConnectorCircuitBreaker   circuitBreaker;
    private final ConnectorHealthService    healthService;
    private final ConnectorFeeService       feeService;
    private final RoutePolicyRepository     routePolicyRepository;
    private final RoutePolicyRouteRepository routePolicyRouteRepository;
    private final RoutePolicyStepRepository routePolicyStepRepository;
    private final ProviderAccountCapabilityService capabilityService;
    private final ObjectMapper              objectMapper;
    private final Random                    random = new Random();

    @Autowired
    public RoutingEngine(RoutingRuleRepository routingRuleRepository,
                         ProviderAccountRepository providerAccountRepository,
                         ConnectorCircuitBreaker circuitBreaker,
                         ConnectorHealthService healthService,
                         ConnectorFeeService feeService,
                         RoutePolicyRepository routePolicyRepository,
                         RoutePolicyRouteRepository routePolicyRouteRepository,
                         RoutePolicyStepRepository routePolicyStepRepository,
                         ProviderAccountCapabilityService capabilityService,
                         ObjectMapper objectMapper) {
        this.routingRuleRepository      = routingRuleRepository;
        this.providerAccountRepository  = providerAccountRepository;
        this.circuitBreaker             = circuitBreaker;
        this.healthService              = healthService;
        this.feeService                 = feeService;
        this.routePolicyRepository      = routePolicyRepository;
        this.routePolicyRouteRepository = routePolicyRouteRepository;
        this.routePolicyStepRepository  = routePolicyStepRepository;
        this.capabilityService          = capabilityService;
        this.objectMapper               = objectMapper;
    }

    public RoutingEngine(RoutingRuleRepository routingRuleRepository,
                         ProviderAccountRepository providerAccountRepository,
                         ConnectorCircuitBreaker circuitBreaker,
                         ConnectorHealthService healthService,
                         ConnectorFeeService feeService) {
        this.routingRuleRepository      = routingRuleRepository;
        this.providerAccountRepository  = providerAccountRepository;
        this.circuitBreaker             = circuitBreaker;
        this.healthService              = healthService;
        this.feeService                 = feeService;
        this.routePolicyRepository      = null;
        this.routePolicyRouteRepository = null;
        this.routePolicyStepRepository  = null;
        this.capabilityService          = null;
        this.objectMapper               = null;
    }

    /**
     * The resolved routing decision: a primary account to charge and an optional fallback.
     * Fallback is used if the primary charge fails.
     */
    public record RoutingResult(ProviderAccount primary, ProviderAccount fallback) {
        public boolean hasFallback() { return fallback != null; }
    }

    /**
     * Resolves a routing decision for a payment.
     *
     * Finds all enabled rules matching the payment criteria.
     * Phase 3.1: prefers rules whose primary account has a healthy success rate (≥80%).
     * Phase 3.2: skips rules whose primary circuit is open when healthy alternatives exist.
     * Falls back to degraded/open connectors only if no healthy ones are available.
     *
     * Returns empty if no rules are configured — caller falls back to resolveAnyAccount().
     */
    public Optional<RoutingResult> resolve(UUID merchantId, long amount, String currency,
                                           String countryCode, String paymentMethodType) {
        List<RoutingRule> rules =
                routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId);

        List<RoutingRule> matching = rules.stream()
                .filter(r -> matches(r, amount, currency, countryCode, paymentMethodType))
                .toList();

        if (matching.isEmpty()) return Optional.empty();

        // Partition: healthy = circuit closed AND success rate ≥ threshold
        List<RoutingRule> healthy = matching.stream()
                .filter(r -> !circuitBreaker.isOpen(r.getTargetAccountId())
                        && healthService.getSuccessRate(r.getTargetAccountId()) >= SUCCESS_RATE_THRESHOLD)
                .toList();

        // Use healthy pool if any exist; otherwise fall back to all matching (no other choice)
        List<RoutingRule> pool = healthy.isEmpty() ? matching : healthy;
        if (healthy.isEmpty()) {
            log.warn("All matching routing rules have degraded primary connectors — using best available");
        }

        // Phase 3.5: apply cost ceiling filter — exclude rules whose target connector
        // exceeds the rule's max_cost_bps threshold for this transaction amount.
        // Only applied when (a) the rule has a ceiling set AND (b) the account has fee config.
        // Falls back to unconstrained pool if every rule is over-budget.
        List<RoutingRule> withinBudget = pool.stream()
                .filter(r -> {
                    if (r.getMaxCostBps() == null) return true; // no ceiling configured
                    ProviderAccount acct = providerAccountRepository.findById(r.getTargetAccountId()).orElse(null);
                    if (acct == null) return true;
                    boolean within = !feeService.exceedsCeiling(acct, amount, r.getMaxCostBps());
                    if (!within) {
                        log.debug("Rule {} excluded: cost {} exceeds ceiling {}bps on amount {}",
                                r.getId(), ConnectorFeeService.describe(acct), r.getMaxCostBps(), amount);
                    }
                    return within;
                })
                .toList();

        if (withinBudget.isEmpty()) {
            log.warn("All matching rules exceed cost ceiling for amount={} — routing without cost constraint", amount);
            withinBudget = pool;
        }

        RoutingRule winner = withinBudget.size() == 1 ? withinBudget.get(0) : pickByWeight(withinBudget);

        ProviderAccount primary = providerAccountRepository.findById(winner.getTargetAccountId())
                .orElse(null);
        if (primary == null) return Optional.empty();

        ProviderAccount fallback = winner.getFallbackAccountId() != null
                ? providerAccountRepository.findById(winner.getFallbackAccountId()).orElse(null)
                : null;

        // If the selected primary's circuit is open and the fallback is healthier, promote fallback
        if (circuitBreaker.isOpen(primary.getId()) && fallback != null
                && !circuitBreaker.isOpen(fallback.getId())) {
            log.info("Primary connector {} circuit open — promoting fallback {} as primary",
                    primary.getId(), fallback.getId());
            return Optional.of(new RoutingResult(fallback, null));
        }

        return Optional.of(new RoutingResult(primary, fallback));
    }

    /**
     * Resolves a versioned route policy first, then falls back to legacy routing rules.
     * Policy steps are concrete provider accounts ordered by step_order, filtered by
     * health, cost, tenant, mode, and declared account capabilities.
     */
    public Optional<RoutePlan> resolvePlan(RoutingContext context) {
        Optional<RoutePlan> policyPlan = resolveActivePolicyPlan(context);
        if (policyPlan.isPresent()) {
            return policyPlan;
        }

        return resolve(context.merchantId(), context.amount(), context.currency(),
                context.country(), context.paymentMethodType())
                .map(RoutePlan::from)
                .or(() -> resolveAnyAccount(context.merchantId(), context.mode()).map(RoutePlan::single));
    }

    private Optional<RoutePlan> resolveActivePolicyPlan(RoutingContext context) {
        if (routePolicyRepository == null) {
            return Optional.empty();
        }

        Optional<RoutePolicy> activePolicy = routePolicyRepository.findByMerchantIdAndModeAndStatus(
                context.merchantId(), context.mode(), RoutePolicyStatus.ACTIVE);
        if (activePolicy.isEmpty()) {
            return Optional.empty();
        }

        RoutePolicy policy = activePolicy.get();
        List<RoutePolicyRoute> routes = routePolicyRouteRepository
                .findAllByMerchantIdAndPolicyIdOrderByRouteOrderAsc(context.merchantId(), policy.getId());
        Optional<RoutePolicyRoute> selectedRoute = routes.stream()
                .filter(route -> matchesPolicyRoute(route, context))
                .findFirst()
                .or(() -> routes.stream().filter(RoutePolicyRoute::isDefaultRoute).findFirst());
        if (selectedRoute.isEmpty()) {
            return Optional.empty();
        }

        List<RouteCandidate> candidates = routePolicyStepRepository
                .findAllByMerchantIdAndRouteIdOrderByStepOrderAsc(context.merchantId(), selectedRoute.get().getId())
                .stream()
                .map(step -> executableCandidate(step, context))
                .flatMap(Optional::stream)
                .toList();

        return candidates.isEmpty() ? Optional.empty() : Optional.of(new RoutePlan(candidates));
    }

    private Optional<RouteCandidate> executableCandidate(RoutePolicyStep step, RoutingContext context) {
        Optional<ProviderAccount> account = providerAccountRepository
                .findByIdAndMerchantId(step.getProviderAccountId(), context.merchantId());
        if (account.isEmpty()) {
            return Optional.empty();
        }

        ProviderAccount candidate = account.get();
        if (candidate.getStatus() != ProviderAccountStatus.ACTIVE || candidate.getMode() != context.mode()) {
            return Optional.empty();
        }
        if (step.isSkipIfDegraded()
                && (circuitBreaker.isOpen(candidate.getId())
                || healthService.getSuccessRate(candidate.getId()) < SUCCESS_RATE_THRESHOLD)) {
            return Optional.empty();
        }
        if (step.getMaxCostBps() != null && feeService.exceedsCeiling(candidate, context.amount(), step.getMaxCostBps())) {
            log.debug("Policy step {} excluded: cost {} exceeds ceiling {}bps on amount {}",
                    step.getId(), ConnectorFeeService.describe(candidate), step.getMaxCostBps(), context.amount());
            return Optional.empty();
        }
        if (capabilityService != null
                && !capabilityService.supportsOrUnspecified(context.merchantId(), candidate.getId(), context)) {
            return Optional.empty();
        }

        return Optional.of(new RouteCandidate(candidate, parseOutcomeActions(step)));
    }

    private Map<String, String> parseOutcomeActions(RoutePolicyStep step) {
        String raw = step.getOutcomeActionsJson();
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            Map<String, String> actions = new HashMap<>();
            root.fields().forEachRemaining(entry -> actions.put(
                    entry.getKey().trim().toUpperCase(Locale.ROOT),
                    entry.getValue().asText().trim().toLowerCase(Locale.ROOT)));
            return actions;
        } catch (Exception e) {
            log.warn("Ignoring invalid outcome actions for route step {}", step.getId());
            return Map.of();
        }
    }

    private boolean matchesPolicyRoute(RoutePolicyRoute route, RoutingContext context) {
        String conditions = route.getConditionsJson();
        if (conditions == null || conditions.isBlank() || "{}".equals(conditions.trim())) {
            return route.isDefaultRoute();
        }

        try {
            JsonNode root = objectMapper.readTree(conditions);
            JsonNode all = root.get("all");
            if (all != null && all.isArray()) {
                for (JsonNode condition : all) {
                    if (!matchesCondition(condition, context)) {
                        return false;
                    }
                }
                return true;
            }
            if (root.has("field")) {
                return matchesCondition(root, context);
            }
            return matchesSimpleConditions(root, context);
        } catch (Exception e) {
            log.warn("Ignoring invalid route conditions for route {}", route.getId());
            return false;
        }
    }

    private boolean matchesSimpleConditions(JsonNode root, RoutingContext context) {
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!matchesValue(field.getKey(), "eq", field.getValue(), context)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCondition(JsonNode condition, RoutingContext context) {
        String field = text(condition.get("field"));
        String operator = text(condition.get("operator"));
        JsonNode value = condition.get("value");
        if (field == null || field.isBlank()) {
            return false;
        }
        return matchesValue(field, operator == null ? "eq" : operator, value, context);
    }

    private boolean matchesValue(String field, String operator, JsonNode expected, RoutingContext context) {
        Object actual = routingValue(field, context);
        String op = normalize(operator);
        if ("missing".equals(op)) {
            return actual == null || actual.toString().isBlank();
        }
        if (actual == null || expected == null || expected.isNull()) {
            return false;
        }

        if (actual instanceof Number number) {
            return matchesNumber(number.longValue(), op, expected);
        }

        String actualText = actual.toString();
        if ("in".equals(op) && expected.isArray()) {
            for (JsonNode item : expected) {
                if (sameText(actualText, item.asText())) {
                    return true;
                }
            }
            return false;
        }
        if ("not_eq".equals(op) || "ne".equals(op)) {
            return !sameText(actualText, expected.asText());
        }
        return sameText(actualText, expected.asText());
    }

    private boolean matchesNumber(long actual, String operator, JsonNode expected) {
        return switch (operator) {
            case "gt" -> actual > expected.asLong();
            case "gte" -> actual >= expected.asLong();
            case "lt" -> actual < expected.asLong();
            case "lte" -> actual <= expected.asLong();
            case "between" -> expected.isArray() && expected.size() == 2
                    && actual >= expected.get(0).asLong() && actual <= expected.get(1).asLong();
            case "not_eq", "ne" -> actual != expected.asLong();
            default -> actual == expected.asLong();
        };
    }

    private Object routingValue(String field, RoutingContext context) {
        return switch (normalizeField(field)) {
            case "amount" -> context.amount();
            case "currency" -> context.currency();
            case "country" -> context.country();
            case "payment_method_type", "payment_method" -> context.paymentMethodType();
            case "capture_method" -> context.captureMethod() != null ? context.captureMethod().name() : null;
            case "customer_id" -> context.customerId();
            case "order_id" -> context.orderId();
            case "instrument_source" -> context.instrumentSource() != null ? context.instrumentSource().name() : null;
            case "instrument_portability" -> context.instrumentPortability() != null
                    ? context.instrumentPortability().name() : null;
            case "card_brand" -> context.cardBrand();
            case "bin_country" -> context.binCountry();
            case "issuer_country" -> context.issuerCountry();
            case "card_type" -> context.cardType();
            case "wallet_type" -> context.walletType();
            default -> metadataValue(field, context);
        };
    }

    private String metadataValue(String field, RoutingContext context) {
        if (context.metadata() == null) {
            return null;
        }
        if (field.startsWith("metadata.")) {
            return context.metadata().get(field.substring("metadata.".length()));
        }
        return null;
    }

    private String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeField(String value) {
        return normalize(value).replace(".", "_");
    }

    private boolean sameText(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    /**
     * Selects an active account for a specific provider brand using weighted-random selection.
     * Used by the tokenize flow where the customer has already chosen a provider.
     *
     * Phase 3.2: filters out accounts with open circuits first; uses all accounts if all are open.
     * Phase 3.1: among circuit-closed candidates, prefers higher success rate via weight adjustment.
     */
    public Optional<ProviderAccount> resolveAccountForProvider(UUID merchantId, PaymentProvider provider, ApiKeyMode mode) {
        List<ProviderAccount> candidates = providerAccountRepository
                .findAllByMerchantIdAndProviderAndModeAndStatus(merchantId, provider, mode, ProviderAccountStatus.ACTIVE);

        return selectAvailableProviderAccount(candidates, provider);
    }

    public Optional<ProviderAccount> resolveAccountForProvider(UUID merchantId, PaymentProvider provider,
                                                               ApiKeyMode mode, RoutingContext context) {
        List<ProviderAccount> candidates = providerAccountRepository
                .findAllByMerchantIdAndProviderAndModeAndStatus(merchantId, provider, mode, ProviderAccountStatus.ACTIVE)
                .stream()
                .filter(account -> supportsCapabilities(account, context))
                .toList();

        return selectAvailableProviderAccount(candidates, provider);
    }

    public boolean supportsCapabilities(ProviderAccount account, RoutingContext context) {
        return account != null
                && (context == null
                || capabilityService == null
                || capabilityService.supportsOrUnspecified(context.merchantId(), account.getId(), context));
    }

    private Optional<ProviderAccount> selectAvailableProviderAccount(List<ProviderAccount> candidates,
                                                                     PaymentProvider provider) {
        if (candidates.isEmpty()) return Optional.empty();
        if (candidates.size() == 1) return Optional.of(candidates.get(0));

        // Prefer accounts with closed circuits; fall back to all if all open
        List<ProviderAccount> available = candidates.stream()
                .filter(a -> !circuitBreaker.isOpen(a.getId()))
                .toList();
        if (available.isEmpty()) {
            log.warn("All {} accounts for provider {} have open circuits — using all", candidates.size(), provider);
            available = candidates;
        }

        return weightedSelect(available);
    }

    /**
     * Fallback when no routing rule matches: returns any single active account for the merchant.
     * Prefers the primary-flagged account; skips circuit-open accounts when alternatives exist.
     */
    public Optional<ProviderAccount> resolveAnyAccount(UUID merchantId, ApiKeyMode mode) {
        List<ProviderAccount> active = providerAccountRepository
                .findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode)
                .stream()
                .filter(a -> a.getStatus() == ProviderAccountStatus.ACTIVE)
                .toList();

        if (active.isEmpty()) return Optional.empty();

        // Filter out open circuits; fall back to all if all open
        List<ProviderAccount> closedCircuit = active.stream()
                .filter(a -> !circuitBreaker.isOpen(a.getId()))
                .toList();
        List<ProviderAccount> available = closedCircuit.isEmpty() ? active : closedCircuit;

        // Prefer primary-flagged account (within the available set)
        return available.stream().filter(ProviderAccount::isPrimary).findFirst()
                .or(() -> available.stream().findFirst());
    }

    /**
     * Returns distinct provider brands that have at least one active connector for this merchant + mode.
     * Circuit-open connectors are still included in the picker — the customer should still see the
     * option; the circuit will either recover by the time they submit or will fail gracefully.
     */
    public List<PaymentProvider> availableProviders(UUID merchantId, ApiKeyMode mode) {
        return providerAccountRepository
                .findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode)
                .stream()
                .filter(a -> a.getStatus() == ProviderAccountStatus.ACTIVE)
                .filter(a -> a.getEncryptedPublishableKey() != null || a.getProviderConfig() != null)
                .map(ProviderAccount::getProvider)
                .distinct()
                .toList();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private RoutingRule pickByWeight(List<RoutingRule> rules) {
        int totalWeight = rules.stream().mapToInt(RoutingRule::getWeight).sum();
        if (totalWeight <= 0) return rules.get(0);

        int pick = random.nextInt(totalWeight);
        int cumulative = 0;
        for (RoutingRule rule : rules) {
            cumulative += rule.getWeight();
            if (pick < cumulative) return rule;
        }
        return rules.get(rules.size() - 1);
    }

    /**
     * Weighted-random selection among accounts, using the configured account weight.
     * Healthy accounts (success rate ≥ threshold) keep their full weight; degraded ones
     * are halved so traffic naturally shifts away from them without fully excluding them.
     */
    private Optional<ProviderAccount> weightedSelect(List<ProviderAccount> accounts) {
        // Compute effective weights: halve for degraded accounts
        int[] weights = new int[accounts.size()];
        int total = 0;
        for (int i = 0; i < accounts.size(); i++) {
            ProviderAccount a = accounts.get(i);
            int w = Math.max(1, a.getWeight());
            if (healthService.getSuccessRate(a.getId()) < SUCCESS_RATE_THRESHOLD) {
                w = Math.max(1, w / 2);
            }
            weights[i] = w;
            total += w;
        }

        int pick = random.nextInt(total);
        int cumulative = 0;
        for (int i = 0; i < accounts.size(); i++) {
            cumulative += weights[i];
            if (pick < cumulative) return Optional.of(accounts.get(i));
        }
        return Optional.of(accounts.get(accounts.size() - 1));
    }

    private boolean matches(RoutingRule rule, long amount, String currency,
                            String countryCode, String paymentMethodType) {
        List<String> currencies = rule.getCurrencyList();
        if (!currencies.isEmpty() && currencies.stream().noneMatch(c -> c.equalsIgnoreCase(currency))) {
            return false;
        }
        if (rule.getAmountMin() != null && amount < rule.getAmountMin()) return false;
        if (rule.getAmountMax() != null && amount > rule.getAmountMax()) return false;

        List<String> codes = rule.getCountryCodeList();
        if (!codes.isEmpty() && countryCode != null
                && codes.stream().noneMatch(c -> c.equalsIgnoreCase(countryCode))) {
            return false;
        }

        List<String> types = rule.getPaymentMethodTypeList();
        if (!types.isEmpty() && paymentMethodType != null
                && types.stream().noneMatch(t -> t.equalsIgnoreCase(paymentMethodType))) {
            return false;
        }

        return true;
    }
}
