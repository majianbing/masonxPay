package com.masonx.paygateway.service;

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

    public RoutingRuleService(RoutingRuleRepository routingRuleRepository) {
        this.routingRuleRepository = routingRuleRepository;
    }

    @Transactional(readOnly = true)
    public List<RoutingRuleResponse> list(UUID merchantId) {
        return routingRuleRepository.findByMerchantId(merchantId)
                .stream().map(RoutingRuleResponse::from).toList();
    }

    public RoutingRuleResponse create(UUID merchantId, CreateRoutingRuleRequest req) {
        RoutingRule rule = new RoutingRule();
        rule.setMerchantId(merchantId);
        applyRequest(rule, req.priority(), req.enabled(), req.currencies(), req.amountMin(),
                req.amountMax(), req.countryCodes(), req.paymentMethodTypes(),
                req.targetProvider(), req.fallbackProvider());
        return RoutingRuleResponse.from(routingRuleRepository.save(rule));
    }

    public RoutingRuleResponse update(UUID merchantId, UUID ruleId, UpdateRoutingRuleRequest req) {
        RoutingRule rule = routingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Routing rule not found"));
        if (!rule.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Routing rule does not belong to this merchant");
        }
        applyRequest(rule, req.priority(), req.enabled(), req.currencies(), req.amountMin(),
                req.amountMax(), req.countryCodes(), req.paymentMethodTypes(),
                req.targetProvider(), req.fallbackProvider());
        return RoutingRuleResponse.from(routingRuleRepository.save(rule));
    }

    public void delete(UUID merchantId, UUID ruleId) {
        RoutingRule rule = routingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new IllegalArgumentException("Routing rule not found"));
        if (!rule.getMerchantId().equals(merchantId)) {
            throw new IllegalArgumentException("Routing rule does not belong to this merchant");
        }
        routingRuleRepository.delete(rule);
    }

    private void applyRequest(RoutingRule rule, int priority, boolean enabled,
                               List<String> currencies, Long amountMin, Long amountMax,
                               List<String> countryCodes, List<String> paymentMethodTypes,
                               com.masonx.paygateway.domain.payment.PaymentProvider targetProvider,
                               com.masonx.paygateway.domain.payment.PaymentProvider fallbackProvider) {
        rule.setPriority(priority);
        rule.setEnabled(enabled);
        rule.setCurrencyList(currencies);
        rule.setAmountMin(amountMin);
        rule.setAmountMax(amountMax);
        rule.setCountryCodeList(countryCodes);
        rule.setPaymentMethodTypeList(paymentMethodTypes);
        rule.setTargetProvider(targetProvider);
        rule.setFallbackProvider(fallbackProvider);
    }
}
