package com.masonx.paygateway.domain.retry;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScheduledRetryJobRepository extends JpaRepository<ScheduledRetryJob, UUID> {
    List<ScheduledRetryJob> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);
    List<ScheduledRetryJob> findByMerchantIdAndStatusOrderByCreatedAtDesc(UUID merchantId, ScheduledRetryStatus status);
    Optional<ScheduledRetryJob> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<ScheduledRetryJob> findByExternalIdAndMerchantId(String externalId, UUID merchantId);
    Optional<ScheduledRetryJob> findFirstByMerchantIdAndOperationAndPaymentIntentIdAndStatusInOrderByCreatedAtDesc(
            UUID merchantId,
            ScheduledRetryOperation operation,
            UUID paymentIntentId,
            Collection<ScheduledRetryStatus> statuses);
    Optional<ScheduledRetryJob> findFirstByMerchantIdAndOperationAndRefundIdAndStatusInOrderByCreatedAtDesc(
            UUID merchantId,
            ScheduledRetryOperation operation,
            UUID refundId,
            Collection<ScheduledRetryStatus> statuses);


    @Query("""
        SELECT j FROM ScheduledRetryJob j
        WHERE j.status = com.masonx.paygateway.domain.retry.ScheduledRetryStatus.SCHEDULED
          AND j.nextRunAt <= :now
        ORDER BY j.nextRunAt ASC
        """)
    List<ScheduledRetryJob> findDue(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT j FROM ScheduledRetryJob j
        WHERE j.id = :id
          AND j.status = com.masonx.paygateway.domain.retry.ScheduledRetryStatus.SCHEDULED
        """)
    Optional<ScheduledRetryJob> findScheduledForUpdate(@Param("id") UUID id);
}
