package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.routing.RoutingRule;
import com.masonx.paygateway.domain.routing.RoutingRuleRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
     * Resolves which provider BRAND to use for a payment.
     * Collects all matching enabled rules and picks one by weighted-random selection,
     * so rules with higher weight receive proportionally more traffic.
     * Falls back to STRIPE if no rule matches.
     */
    public PaymentProvider resolve(UUID merchantId, long amount, String currency,
                                   String countryCode, String paymentMethodType) {
        List<RoutingRule> rules =
                routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId);

        List<RoutingRule> matching = rules.stream()
                .filter(r -> matches(r, amount, currency, countryCode, paymentMethodType))
                .toList();

        if (matching.isEmpty()) return PaymentProvider.STRIPE;
        if (matching.size() == 1) return matching.get(0).getTargetProvider();

        int totalWeight = matching.stream().mapToInt(RoutingRule::getWeight).sum();
        if (totalWeight <= 0) return matching.get(0).getTargetProvider();

        int pick = random.nextInt(totalWeight);
        int cumulative = 0;
        for (RoutingRule rule : matching) {
            cumulative += rule.getWeight();
            if (pick < cumulative) return rule.getTargetProvider();
        }
        return matching.get(matching.size() - 1).getTargetProvider();
    }

    /**
     * Selects a specific connector account within a provider brand using weighted-random selection.
     * Among all ACTIVE accounts for merchant + provider + mode, picks one proportionally to weight.
     * Falls back to the primary account if no accounts have weight > 0.
     */
    public Optional<ProviderAccount> resolveAccount(UUID merchantId, PaymentProvider provider, ApiKeyMode mode) {
        List<ProviderAccount> candidates = providerAccountRepository
                .findAllByMerchantIdAndProviderAndModeAndStatus(merchantId, provider, mode, ProviderAccountStatus.ACTIVE);

        if (candidates.isEmpty()) return Optional.empty();
        if (candidates.size() == 1) return Optional.of(candidates.get(0));

        int totalWeight = candidates.stream().mapToInt(ProviderAccount::getWeight).sum();
        if (totalWeight <= 0) {
            // Degenerate case — fall back to first (primary if sorted)
            return Optional.of(candidates.get(0));
        }

        int pick = random.nextInt(totalWeight);
        int cumulative = 0;
        for (ProviderAccount account : candidates) {
            cumulative += account.getWeight();
            if (pick < cumulative) return Optional.of(account);
        }

        return Optional.of(candidates.get(candidates.size() - 1));
    }

    /**
     * Returns all active connector accounts for merchant + provider + mode, excluding already-tried
     * ones, in weighted-random order. Used by the failover loop in PaymentIntentService so each
     * candidate is tried at most once per confirm call, with higher-weight accounts preferred.
     */
    public List<ProviderAccount> resolveAccountsOrdered(UUID merchantId, PaymentProvider provider,
                                                        ApiKeyMode mode, Set<UUID> excludeIds) {
        List<ProviderAccount> candidates = providerAccountRepository
                .findAllByMerchantIdAndProviderAndModeAndStatus(merchantId, provider, mode, ProviderAccountStatus.ACTIVE)
                .stream()
                .filter(a -> !excludeIds.contains(a.getId()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        if (candidates.isEmpty()) return Collections.emptyList();
        if (candidates.size() == 1) return candidates;

        // Weighted shuffle: repeatedly pick by weight and move to result list
        List<ProviderAccount> ordered = new ArrayList<>(candidates.size());
        while (!candidates.isEmpty()) {
            int totalWeight = candidates.stream().mapToInt(ProviderAccount::getWeight).sum();
            if (totalWeight <= 0) {
                ordered.addAll(candidates);
                break;
            }
            int pick = random.nextInt(totalWeight);
            int cumulative = 0;
            for (int i = 0; i < candidates.size(); i++) {
                cumulative += candidates.get(i).getWeight();
                if (pick < cumulative) {
                    ordered.add(candidates.remove(i));
                    break;
                }
            }
        }
        return ordered;
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
