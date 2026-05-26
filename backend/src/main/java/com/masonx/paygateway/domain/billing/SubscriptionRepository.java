package com.masonx.paygateway.domain.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    List<Subscription> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
    List<Subscription> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode);
    List<Subscription> findByMerchantIdAndCustomerIdOrderByCreatedAtDesc(UUID merchantId, UUID customerId);
    List<Subscription> findByMerchantIdAndModeAndCustomerIdOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode, UUID customerId);
    Optional<Subscription> findByIdAndMerchantId(UUID id, UUID merchantId);
}
