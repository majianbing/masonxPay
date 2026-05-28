package com.masonx.paygateway.domain.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, UUID> {
    List<BillingCustomer> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
    List<BillingCustomer> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode);
    Optional<BillingCustomer> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<BillingCustomer> findByIdAndMerchantIdAndMode(UUID id, UUID merchantId, ApiKeyMode mode);
}
