package com.masonx.paygateway.domain.dispute;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    Optional<Dispute> findByProviderDisputeId(String providerDisputeId);

    Optional<Dispute> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<Dispute> findByExternalIdAndMerchantId(String externalId, UUID merchantId);

    Page<Dispute> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode, Pageable pageable);

    Page<Dispute> findByMerchantIdAndModeAndStatusOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode, DisputeStatus status, Pageable pageable);
}
