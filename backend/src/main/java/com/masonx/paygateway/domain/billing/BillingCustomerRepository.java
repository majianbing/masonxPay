package com.masonx.paygateway.domain.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, UUID> {
    List<BillingCustomer> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
    Optional<BillingCustomer> findByIdAndMerchantId(UUID id, UUID merchantId);
}
