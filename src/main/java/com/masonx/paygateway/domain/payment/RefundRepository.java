package com.masonx.paygateway.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIntentId(UUID paymentIntentId);
    List<Refund> findByMerchantId(UUID merchantId);
}
