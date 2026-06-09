package com.masonx.paygateway.domain.dispute;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DisputeRepository extends JpaRepository<Dispute, UUID> {

    Optional<Dispute> findByProviderDisputeId(String providerDisputeId);

    Optional<Dispute> findByIdAndMerchantId(UUID id, UUID merchantId);

    Page<Dispute> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<Dispute> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, DisputeStatus status, Pageable pageable);
}
