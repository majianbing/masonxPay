package com.masonx.paygateway.domain.routing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoutingRuleRepository extends JpaRepository<RoutingRule, UUID> {
    List<RoutingRule> findByMerchantId(UUID merchantId);
    List<RoutingRule> findByMerchantIdAndEnabledTrueOrderByPriorityAsc(UUID merchantId);
}
