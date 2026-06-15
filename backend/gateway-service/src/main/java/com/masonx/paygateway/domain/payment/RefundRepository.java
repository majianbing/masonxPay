package com.masonx.paygateway.domain.payment;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, UUID>, JpaSpecificationExecutor<Refund> {
    List<Refund> findByPaymentIntentId(UUID paymentIntentId);
    List<Refund> findByMerchantId(UUID merchantId);
    Page<Refund> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.id = :id AND r.merchantId = :merchantId")
    Optional<Refund> findByIdAndMerchantIdForUpdate(@Param("id") UUID id, @Param("merchantId") UUID merchantId);

    /** Sum of PENDING + SUCCEEDED refunds for this intent — used to prevent over-refunding. */
    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r " +
           "WHERE r.paymentIntentId = :intentId AND r.status IN " +
           "(com.masonx.paygateway.domain.payment.RefundStatus.PENDING, " +
           "com.masonx.paygateway.domain.payment.RefundStatus.SUCCEEDED)")
    long sumActiveByPaymentIntentId(@Param("intentId") UUID intentId);

    @Query("SELECT COALESCE(SUM(r.amount), 0) FROM Refund r " +
           "WHERE r.paymentIntentId = :intentId AND r.status = com.masonx.paygateway.domain.payment.RefundStatus.SUCCEEDED")
    long sumSucceededByPaymentIntentId(@Param("intentId") UUID intentId);

    // --- Analytics aggregation queries ---

    /** Returns [status (enum), count (Long), totalAmount (Long)] rows. */
    @Query("""
        SELECT r.status, COUNT(r), SUM(r.amount)
        FROM Refund r
        WHERE r.merchantId = :merchantId AND r.mode = :mode
          AND r.createdAt BETWEEN :from AND :to
        GROUP BY r.status
        """)
    List<Object[]> findGroupedByStatus(
        @Param("merchantId") UUID merchantId,
        @Param("mode") ApiKeyMode mode,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    /** Returns [reason (enum or null), count (Long), totalAmount (Long)] rows. */
    @Query("""
        SELECT r.reason, COUNT(r), SUM(r.amount)
        FROM Refund r
        WHERE r.merchantId = :merchantId AND r.mode = :mode
          AND r.createdAt BETWEEN :from AND :to
        GROUP BY r.reason
        """)
    List<Object[]> findGroupedByReason(
        @Param("merchantId") UUID merchantId,
        @Param("mode") ApiKeyMode mode,
        @Param("from") Instant from,
        @Param("to") Instant to
    );

    /** Minimal projection for daily time-series grouping in the service layer. */
    @Query("""
        SELECT r.createdAt, r.amount, r.status
        FROM Refund r
        WHERE r.merchantId = :merchantId AND r.mode = :mode
          AND r.createdAt BETWEEN :from AND :to
        ORDER BY r.createdAt
        """)
    List<Object[]> findRawForTimeSeries(
        @Param("merchantId") UUID merchantId,
        @Param("mode") ApiKeyMode mode,
        @Param("from") Instant from,
        @Param("to") Instant to
    );
}
