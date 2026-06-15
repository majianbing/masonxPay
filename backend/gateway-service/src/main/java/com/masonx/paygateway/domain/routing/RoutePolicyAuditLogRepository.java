package com.masonx.paygateway.domain.routing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoutePolicyAuditLogRepository extends JpaRepository<RoutePolicyAuditLog, UUID> {
    List<RoutePolicyAuditLog> findAllByMerchantIdAndPolicyIdOrderByCreatedAtDesc(UUID merchantId, UUID policyId);
}
