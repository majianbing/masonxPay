package com.masonx.paygateway.domain.log;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GatewayLogRepository extends JpaRepository<GatewayLog, UUID> {
    Page<GatewayLog> findAllByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);
    Page<GatewayLog> findAllByMerchantIdAndTypeOrderByCreatedAtDesc(UUID merchantId, GatewayLogType type, Pageable pageable);
}
