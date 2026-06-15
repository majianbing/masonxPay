package com.masonx.paygateway.domain.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubscriptionItemRepository extends JpaRepository<SubscriptionItem, UUID> {
    List<SubscriptionItem> findByMerchantIdAndSubscriptionIdOrderByCreatedAtAsc(UUID merchantId, UUID subscriptionId);
}
