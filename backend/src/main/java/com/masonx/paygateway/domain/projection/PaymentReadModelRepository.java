package com.masonx.paygateway.domain.projection;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface PaymentReadModelRepository extends JpaRepository<PaymentReadModel, UUID>, JpaSpecificationExecutor<PaymentReadModel> {
    Optional<PaymentReadModel> findByPaymentIntentIdAndMerchantId(UUID paymentIntentId, UUID merchantId);
}
