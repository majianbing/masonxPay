package com.masonx.paygateway.domain.log;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface GatewayLogRepository extends JpaRepository<GatewayLog, UUID> {
    Page<GatewayLog> findAllByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);
    Page<GatewayLog> findAllByMerchantIdAndTypeOrderByCreatedAtDesc(UUID merchantId, GatewayLogType type, Pageable pageable);

    // Include logs where mode matches OR mode is null (JWT/dashboard requests have no mode)
    @Query("SELECT l FROM GatewayLog l WHERE l.merchantId = :merchantId AND (l.mode = :mode OR l.mode IS NULL) ORDER BY l.createdAt DESC")
    Page<GatewayLog> findByMerchantIdAndModeOrNull(@Param("merchantId") UUID merchantId, @Param("mode") ApiKeyMode mode, Pageable pageable);

    @Query("SELECT l FROM GatewayLog l WHERE l.merchantId = :merchantId AND l.type = :type AND (l.mode = :mode OR l.mode IS NULL) ORDER BY l.createdAt DESC")
    Page<GatewayLog> findByMerchantIdAndTypeAndModeOrNull(@Param("merchantId") UUID merchantId, @Param("type") GatewayLogType type, @Param("mode") ApiKeyMode mode, Pageable pageable);
}
