package com.masonx.paygateway.domain.routing;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoutingAttributeRepository extends JpaRepository<RoutingAttribute, UUID> {
    Optional<RoutingAttribute> findByMerchantIdAndKey(UUID merchantId, String key);
    List<RoutingAttribute> findAllByMerchantIdAndEnabledTrueOrderByKeyAsc(UUID merchantId);
}
