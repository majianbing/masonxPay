package com.masonx.paygateway.domain.payment;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID>, JpaSpecificationExecutor<PaymentIntent> {
    Optional<PaymentIntent> findByIdAndMerchantId(UUID id, UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentIntent p WHERE p.id = :id AND p.merchantId = :merchantId")
    Optional<PaymentIntent> findByIdAndMerchantIdForUpdate(@Param("id") UUID id,
                                                           @Param("merchantId") UUID merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentIntent p WHERE p.id = :id")
    Optional<PaymentIntent> findByIdForUpdate(@Param("id") UUID id);

    Optional<PaymentIntent> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);
    List<PaymentIntent> findByMerchantId(UUID merchantId);
    Page<PaymentIntent> findByMerchantId(UUID merchantId, Pageable pageable);
    Page<PaymentIntent> findByMerchantIdAndStatus(UUID merchantId, PaymentIntentStatus status, Pageable pageable);
    Page<PaymentIntent> findByMerchantIdAndMode(UUID merchantId, ApiKeyMode mode, Pageable pageable);
    Page<PaymentIntent> findByMerchantIdAndStatusAndMode(UUID merchantId, PaymentIntentStatus status, ApiKeyMode mode, Pageable pageable);
    Optional<PaymentIntent> findByProviderPaymentId(String providerPaymentId);

    /**
     * Finds PROCESSING or REQUIRES_ACTION intents that have been stale long enough to warrant a sync/cancel:
     *  - card payments pending longer than {@code cardThreshold}  (e.g. 30 minutes)
     *  - all other / unknown payment methods pending longer than {@code otherThreshold} (e.g. 7 days)
     * NULL paymentMethodType is treated conservatively as non-card (longer threshold).
     * REQUIRES_ACTION intents are 3DS challenges abandoned without the customer completing auth.
     */
    @Query("""
        SELECT p FROM PaymentIntent p
        WHERE p.status IN (
                com.masonx.paygateway.domain.payment.PaymentIntentStatus.PROCESSING,
                com.masonx.paygateway.domain.payment.PaymentIntentStatus.REQUIRES_ACTION
              )
          AND (
            (p.paymentMethodType = 'card'                                         AND p.updatedAt < :cardThreshold)
            OR
            ((p.paymentMethodType IS NULL OR p.paymentMethodType <> 'card')       AND p.updatedAt < :otherThreshold)
          )
        ORDER BY p.updatedAt ASC
        """)
    List<PaymentIntent> findStaleProcessing(@Param("cardThreshold") Instant cardThreshold,
                                            @Param("otherThreshold") Instant otherThreshold,
                                            Pageable pageable);

    /**
     * Count of PROCESSING intents older than {@code threshold} — used as a stale-intent
     * gauge for the Prometheus alerting rule (Phase 2.4 / 2.6).
     */
    @Query("""
        SELECT COUNT(p) FROM PaymentIntent p
        WHERE p.status = com.masonx.paygateway.domain.payment.PaymentIntentStatus.PROCESSING
          AND p.updatedAt < :threshold
        """)
    long countStaleProcessing(@Param("threshold") Instant threshold);

    /**
     * Re-reads a single PROCESSING or REQUIRES_ACTION intent with a row-level lock. If another
     * node already holds the row lock, this waits; after the first transaction commits, the
     * status predicate is rechecked so two nodes do not both reconcile the same active intent.
     *
     * Must be called from within an active transaction (txTemplate.execute).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT p FROM PaymentIntent p
        WHERE p.id = :id
          AND p.status IN (
            com.masonx.paygateway.domain.payment.PaymentIntentStatus.PROCESSING,
            com.masonx.paygateway.domain.payment.PaymentIntentStatus.REQUIRES_ACTION
          )
        """)
    Optional<PaymentIntent> findByIdProcessingForUpdate(@Param("id") UUID id);
}
