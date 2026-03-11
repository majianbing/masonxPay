package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.payment.PaymentProvider;
import com.masonx.paygateway.domain.routing.RoutingRule;
import com.masonx.paygateway.domain.routing.RoutingRuleRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RoutingEngine {

    private final RoutingRuleRepository routingRuleRepository;

    public RoutingEngine(RoutingRuleRepository routingRuleRepository) {
        this.routingRuleRepository = routingRuleRepository;
    }

    /**
     * Evaluates enabled routing rules in priority order and returns the first matching provider.
     * Falls back to STRIPE if no rule matches (MVP default).
     */
    public PaymentProvider resolve(UUID merchantId, long amount, String currency,
                                   String countryCode, String paymentMethodType) {
        List<RoutingRule> rules =
                routingRuleRepository.findByMerchantIdAndEnabledTrueOrderByPriorityAsc(merchantId);

        return rules.stream()
                .filter(r -> matches(r, amount, currency, countryCode, paymentMethodType))
                .findFirst()
                .map(RoutingRule::getTargetProvider)
                .orElse(PaymentProvider.STRIPE);  // default
    }

    private boolean matches(RoutingRule rule, long amount, String currency,
                            String countryCode, String paymentMethodType) {
        // Currency check — empty list means match all
        List<String> currencies = rule.getCurrencyList();
        if (!currencies.isEmpty() && currencies.stream().noneMatch(c -> c.equalsIgnoreCase(currency))) {
            return false;
        }

        // Amount range check
        if (rule.getAmountMin() != null && amount < rule.getAmountMin()) return false;
        if (rule.getAmountMax() != null && amount > rule.getAmountMax()) return false;

        // Country code check — null input means skip check
        List<String> codes = rule.getCountryCodeList();
        if (!codes.isEmpty() && countryCode != null
                && codes.stream().noneMatch(c -> c.equalsIgnoreCase(countryCode))) {
            return false;
        }

        // Payment method type check — null input means skip check
        List<String> types = rule.getPaymentMethodTypeList();
        if (!types.isEmpty() && paymentMethodType != null
                && types.stream().noneMatch(t -> t.equalsIgnoreCase(paymentMethodType))) {
            return false;
        }

        return true;
    }
}
