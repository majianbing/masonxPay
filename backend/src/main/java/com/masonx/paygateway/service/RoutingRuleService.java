package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.routing.RoutingRule;
import com.masonx.paygateway.domain.routing.RoutingRuleRepository;
import com.masonx.paygateway.web.dto.CreateRoutingRuleRequest;
import com.masonx.paygateway.web.dto.RoutingRuleResponse;
import com.masonx.paygateway.web.dto.UpdateRoutingRuleRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class RoutingRuleService {

    private final RoutingRuleRepository routingRuleRepository;
    private final ProviderAccountRepository providerAccountRepository;

    public RoutingRuleService(RoutingRuleRepository routingRuleRepository,
                              ProviderAccountRepository providerAccountRepository) {
        this.routingRuleRepository = routingRuleRepository;
        this.providerAccountRepository = providerAccountRepository;
    }

    @Transactional(readOnly = true)
    public List<RoutingRuleResponse> list(UUID merchantId) {
        return routingRuleRepository.findByMerchantId(merchantId)
                .stream().map(r -> toResponse(merchantId, r)).toList();
    }

    public RoutingRuleResponse create(UUID merchantId, CreateRoutingRuleRequest req) {
        ProviderAccount target = requireOwnedAccount(merchantId, req.targetAccountId(), "targetAccountId");
        ProviderAccount fallback = req.fallbackAccountId() != null
                ? requireOwnedAccount(merchantId, req.fallbackAccountId(), "fallbackAccountId")
                : null;

        RoutingRule rule = new RoutingRule();
        rule.setMerchantId(merchantId);
        applyRequest(rule, req.priority(), req.enabled(), req.weight(), req.currencies(),
                req.amountMin(), req.amountMax(), req.countryCodes(), req.paymentMethodTypes(),
                req.targetAccountId(), req.fallbackAccountId());
        return RoutingRuleResponse.from(routingRuleRepository.save(rule), target, fallback);
    }

    public RoutingRuleResponse update(UUID merchantId, UUID ruleId, UpdateRoutingRuleRequest req) {
        RoutingRule rule = routingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Routing rule not found"));
        if (!rule.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Routing rule does not belong to this merchant");
        }

        ProviderAccount target = requireOwnedAccount(merchantId, req.targetAccountId(), "targetAccountId");
        ProviderAccount fallback = req.fallbackAccountId() != null
                ? requireOwnedAccount(merchantId, req.fallbackAccountId(), "fallbackAccountId")
                : null;

        applyRequest(rule, req.priority(), req.enabled(), req.weight(), req.currencies(),
                req.amountMin(), req.amountMax(), req.countryCodes(), req.paymentMethodTypes(),
                req.targetAccountId(), req.fallbackAccountId());
        return RoutingRuleResponse.from(routingRuleRepository.save(rule), target, fallback);
    }

    public void delete(UUID merchantId, UUID ruleId) {
        RoutingRule rule = routingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Routing rule not found"));
        if (!rule.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Routing rule does not belong to this merchant");
        }
        routingRuleRepository.delete(rule);
    }

    private ProviderAccount requireOwnedAccount(UUID merchantId, UUID accountId, String field) {
        ProviderAccount account = providerAccountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException(field + ": connector account not found"));
        if (!account.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException(field + ": connector account does not belong to this merchant");
        }
        return account;
    }

    private RoutingRuleResponse toResponse(UUID merchantId, RoutingRule rule) {
        ProviderAccount target = providerAccountRepository.findById(rule.getTargetAccountId()).orElse(null);
        ProviderAccount fallback = rule.getFallbackAccountId() != null
                ? providerAccountRepository.findById(rule.getFallbackAccountId()).orElse(null)
                : null;
        return RoutingRuleResponse.from(rule, target, fallback);
    }

    private void applyRequest(RoutingRule rule, int priority, boolean enabled, int weight,
                               List<String> currencies, Long amountMin, Long amountMax,
                               List<String> countryCodes, List<String> paymentMethodTypes,
                               UUID targetAccountId, UUID fallbackAccountId) {
        rule.setPriority(priority);
        rule.setEnabled(enabled);
        rule.setWeight(weight > 0 ? weight : 1);
        rule.setCurrencyList(currencies);
        rule.setAmountMin(amountMin);
        rule.setAmountMax(amountMax);
        rule.setCountryCodeList(countryCodes);
        rule.setPaymentMethodTypeList(paymentMethodTypes);
        rule.setTargetAccountId(targetAccountId);
        rule.setFallbackAccountId(fallbackAccountId);
    }
}
