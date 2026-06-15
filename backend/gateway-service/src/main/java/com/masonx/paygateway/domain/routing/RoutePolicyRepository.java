package com.masonx.paygateway.domain.routing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoutePolicyRepository extends JpaRepository<RoutePolicy, UUID> {
    List<RoutePolicy> findAllByMerchantIdAndModeOrderByUpdatedAtDesc(UUID merchantId, ApiKeyMode mode);
    List<RoutePolicy> findAllByMerchantIdOrderByUpdatedAtDesc(UUID merchantId);
    Optional<RoutePolicy> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<RoutePolicy> findByMerchantIdAndModeAndStatus(UUID merchantId, ApiKeyMode mode, RoutePolicyStatus status);
}
