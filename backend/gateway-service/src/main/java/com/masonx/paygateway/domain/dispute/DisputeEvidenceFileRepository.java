package com.masonx.paygateway.domain.dispute;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeEvidenceFileRepository extends JpaRepository<DisputeEvidenceFile, UUID> {

    List<DisputeEvidenceFile> findAllByDisputeId(UUID disputeId);

    Optional<DisputeEvidenceFile> findByIdAndMerchantId(UUID id, UUID merchantId);
}
