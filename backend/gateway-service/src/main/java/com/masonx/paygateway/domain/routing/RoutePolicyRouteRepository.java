package com.masonx.paygateway.domain.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface RoutePolicyRouteRepository extends JpaRepository<RoutePolicyRoute, UUID> {
    List<RoutePolicyRoute> findAllByMerchantIdAndPolicyIdOrderByRouteOrderAsc(UUID merchantId, UUID policyId);
    void deleteAllByMerchantIdAndPolicyId(UUID merchantId, UUID policyId);
}
