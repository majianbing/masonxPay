package com.masonx.paygateway.domain.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionCheckoutLinkRepository extends JpaRepository<SubscriptionCheckoutLink, UUID> {
    List<SubscriptionCheckoutLink> findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(UUID merchantId, UUID subscriptionId);
    Optional<SubscriptionCheckoutLink> findByToken(String token);

    @Modifying
    @Transactional
    @Query("""
        UPDATE SubscriptionCheckoutLink l
        SET l.status = 'PROCESSING'
        WHERE l.token = :token
          AND l.status = 'ACTIVE'
          AND (l.expiresAt IS NULL OR l.expiresAt > CURRENT_TIMESTAMP)
        """)
    int claimLink(@Param("token") String token);

    @Modifying
    @Transactional
    @Query("""
        UPDATE SubscriptionCheckoutLink l
        SET l.status = 'ACTIVE'
        WHERE l.token = :token
          AND l.status = 'PROCESSING'
        """)
    void releaseLink(@Param("token") String token);
}
