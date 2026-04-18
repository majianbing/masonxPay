package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.routing.RoutingRule;
import com.masonx.paygateway.domain.routing.RoutingRuleRepository;
import com.masonx.paygateway.health.ConnectorHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
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
    private final Random                    random = new Random();

    public RoutingEngine(RoutingRuleRepository routingRuleRepository,
                         ProviderAccountRepository providerAccountRepository,
                         ConnectorCircuitBreaker circuitBreaker,
                         ConnectorHealthService healthService) {
        this.routingRuleRepository     = routingRuleRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.circuitBreaker            = circuitBreaker;
        this.healthService             = healthService;
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

        RoutingRule winner = pool.size() == 1 ? pool.get(0) : pickByWeight(pool);

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
     * Selects an active account for a specific provider brand using weighted-random selection.
     * Used by the tokenize flow where the customer has already chosen a provider.
     *
     * Phase 3.2: filters out accounts with open circuits first; uses all accounts if all are open.
     * Phase 3.1: among circuit-closed candidates, prefers higher success rate via weight adjustment.
     */
    public Optional<ProviderAccount> resolveAccountForProvider(UUID merchantId, PaymentProvider provider, ApiKeyMode mode) {
        List<ProviderAccount> candidates = providerAccountRepository
                .findAllByMerchantIdAndProviderAndModeAndStatus(merchantId, provider, mode, ProviderAccountStatus.ACTIVE);

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
