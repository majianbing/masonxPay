package com.masonx.paygateway.domain.payment;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentLinkRepository extends JpaRepository<PaymentLink, UUID> {
    List<PaymentLink> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode);
    Optional<PaymentLink> findByToken(String token);
    Optional<PaymentLink> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Atomically claims the link by flipping ACTIVE → INACTIVE. Returns 1 if claimed, 0 if already inactive/expired. */
    @Modifying
    @Transactional
    @Query("UPDATE PaymentLink l SET l.status = 'INACTIVE' WHERE l.token = :token AND l.status = 'ACTIVE' AND (l.expiresAt IS NULL OR l.expiresAt > CURRENT_TIMESTAMP)")
    int claimLink(@Param("token") String token);

    /** Releases a previously claimed link back to ACTIVE (called when the charge fails so the customer can retry). */
    @Modifying
    @Transactional
    @Query("UPDATE PaymentLink l SET l.status = 'ACTIVE' WHERE l.token = :token")
    void releaseLink(@Param("token") String token);
}
