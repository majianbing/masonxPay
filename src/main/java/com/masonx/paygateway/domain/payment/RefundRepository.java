package com.masonx.paygateway.domain.payment;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {
    List<Refund> findByPaymentIntentId(UUID paymentIntentId);
    List<Refund> findByMerchantId(UUID merchantId);
    Page<Refund> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode, Pageable pageable);
}
