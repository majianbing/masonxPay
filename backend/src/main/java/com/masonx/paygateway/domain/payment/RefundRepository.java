package com.masonx.paygateway.domain.payment;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID>, JpaSpecificationExecutor<Refund> {
    List<Refund> findByPaymentIntentId(UUID paymentIntentId);
    List<Refund> findByMerchantId(UUID merchantId);
    Page<Refund> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode, Pageable pageable);

    /** Sum of PENDING + SUCCEEDED refunds for this intent — used to prevent over-refunding. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r " +
           "WHERE r.paymentIntentId = :intentId AND r.status IN " +
           "(com.masonx.paygateway.domain.payment.RefundStatus.PENDING, " +
           "com.masonx.paygateway.domain.payment.RefundStatus.SUCCEEDED)")
    long sumActiveByPaymentIntentId(@Param("intentId") UUID intentId);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r " +
           "WHERE r.paymentIntentId = :intentId AND r.status = com.masonx.paygateway.domain.payment.RefundStatus.SUCCEEDED")
    long sumSucceededByPaymentIntentId(@Param("intentId") UUID intentId);
}
