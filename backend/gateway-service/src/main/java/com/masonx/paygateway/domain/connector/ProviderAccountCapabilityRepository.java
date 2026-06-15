package com.masonx.paygateway.domain.connector;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProviderAccountCapabilityRepository extends JpaRepository<ProviderAccountCapability, UUID> {
    List<ProviderAccountCapability> findAllByMerchantIdAndProviderAccountIdAndEnabledTrue(
            UUID merchantId, UUID providerAccountId);

    List<ProviderAccountCapability> findAllByMerchantIdAndProviderAccountIdOrderByCreatedAtAsc(
            UUID merchantId, UUID providerAccountId);
}
