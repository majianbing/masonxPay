package com.masonx.paygateway.domain.billing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    List<Subscription> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
    List<Subscription> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode);
    List<Subscription> findByMerchantIdAndCustomerIdOrderByCreatedAtDesc(UUID merchantId, UUID customerId);
    List<Subscription> findByMerchantIdAndModeAndCustomerIdOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode, UUID customerId);
    Optional<Subscription> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Query("SELECT s FROM Subscription s WHERE s.status = :status AND s.currentPeriodEnd < :now ORDER BY s.currentPeriodEnd ASC")
    List<Subscription> findByStatusAndCurrentPeriodEndBefore(
            @Param("status") SubscriptionStatus status,
            @Param("now") Instant now,
            Pageable pageable);
}
