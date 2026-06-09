package com.masonx.paygateway.domain.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantAuditLogRepository extends JpaRepository<MerchantAuditLog, UUID> {

    Page<MerchantAuditLog> findAllByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<MerchantAuditLog> findAllByMerchantIdAndActionOrderByCreatedAtDesc(UUID merchantId, AuditAction action, Pageable pageable);
}
