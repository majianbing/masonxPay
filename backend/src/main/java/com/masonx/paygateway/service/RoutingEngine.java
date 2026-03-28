package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.routing.RoutingRule;
import com.masonx.paygateway.domain.routing.RoutingRuleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@Service
public class RoutingEngine {

    private final RoutingRuleRepository routingRuleRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final Random random = new Random();

    public RoutingEngine(RoutingRuleRepository routingRuleRepository,
                         ProviderAccountRepository providerAccountRepository) {
        this.routingRuleRepository = routingRuleRepository;
        this.providerAccountRepository = providerAccountRepository;
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
     * Finds all enabled rules matching the payment criteria, picks one by weighted-random
     * selection, then loads the target and fallback accounts directly from the winning rule.
     *
     * Returns empty if no rules are configured — caller should fall back to any active account.
     */
    public Optional<RoutingResult> resolve(UUID merchantId, long amount, String currency,
                                           String countryCode, String paymentMethodType) {
        List<RoutingRule> rules =
                routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId);

        List<RoutingRule> matching = rules.stream()
                .filter(r -> matches(r, amount, currency, countryCode, paymentMethodType))
                .toList();

        if (matching.isEmpty()) return Optional.empty();

        RoutingRule winner = matching.size() == 1
                ? matching.get(0)
                : pickByWeight(matching);

        ProviderAccount primary = providerAccountRepository.findById(winner.getTargetAccountId())
                .orElse(null);
        if (primary == null) return Optional.empty();

        ProviderAccount fallback = winner.getFallbackAccountId() != null
                ? providerAccountRepository.findById(winner.getFallbackAccountId()).orElse(null)
                : null;

        return Optional.of(new RoutingResult(primary, fallback));
    }

    /**
     * Selects an active account for a specific provider brand using weighted-random selection.
     * Used by the tokenize flow where the customer has already chosen a provider from the picker
     * and we need to pin an account before routing rules are consulted.
     */
    public Optional<ProviderAccount> resolveAccountForProvider(UUID merchantId, PaymentProvider provider, ApiKeyMode mode) {
        List<ProviderAccount> candidates = providerAccountRepository
                .findAllByMerchantIdAndProviderAndModeAndStatus(merchantId, provider, mode, ProviderAccountStatus.ACTIVE);

        if (candidates.isEmpty()) return Optional.empty();
        if (candidates.size() == 1) return Optional.of(candidates.get(0));

        int totalWeight = candidates.stream().mapToInt(ProviderAccount::getWeight).sum();
        if (totalWeight <= 0) return Optional.of(candidates.get(0));

        int pick = random.nextInt(totalWeight);
        int cumulative = 0;
        for (ProviderAccount account : candidates) {
            cumulative += account.getWeight();
            if (pick < cumulative) return Optional.of(account);
        }
        return Optional.of(candidates.get(candidates.size() - 1));
    }

    /**
     * Fallback when no routing rule matches: returns any single active account for the merchant.
     * Prefers the primary-flagged account; otherwise picks the first active one found.
     */
    public Optional<ProviderAccount> resolveAnyAccount(UUID merchantId, ApiKeyMode mode) {
        List<ProviderAccount> active = providerAccountRepository
                .findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode)
                .stream()
                .filter(a -> a.getStatus() == ProviderAccountStatus.ACTIVE)
                .toList();

        return active.stream().filter(ProviderAccount::isPrimary).findFirst()
                .or(() -> active.stream().findFirst());
    }

    /**
     * Returns distinct provider brands that have at least one active connector for this merchant + mode.
     * Used by /pub/checkout-session to populate the provider picker.
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
