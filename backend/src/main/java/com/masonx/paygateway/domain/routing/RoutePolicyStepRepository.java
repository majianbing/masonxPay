package com.masonx.paygateway.domain.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RoutePolicyStepRepository extends JpaRepository<RoutePolicyStep, UUID> {
    List<RoutePolicyStep> findAllByMerchantIdAndPolicyIdOrderByRouteIdAscStepOrderAsc(UUID merchantId, UUID policyId);
    List<RoutePolicyStep> findAllByMerchantIdAndRouteIdOrderByStepOrderAsc(UUID merchantId, UUID routeId);
    void deleteAllByMerchantIdAndPolicyId(UUID merchantId, UUID policyId);
}
