package com.masonx.paygateway.domain.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findByMerchantIdAndSubscriptionIdOrderByCreatedAtDesc(UUID merchantId, UUID subscriptionId);
    List<Invoice> findByMerchantIdAndModeOrderByCreatedAtDesc(UUID merchantId, ApiKeyMode mode);
    Optional<Invoice> findByIdAndMerchantId(UUID id, UUID merchantId);
    Optional<Invoice> findByMerchantIdAndSubscriptionIdAndPeriodStartAndPeriodEnd(
            UUID merchantId, UUID subscriptionId, Instant periodStart, Instant periodEnd);

    /**
     * Finds OPEN invoices whose next payment attempt is due, using SKIP LOCKED so
     * concurrent billing worker instances claim separate rows without contention.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // SKIP_LOCKED
    @Query("SELECT i FROM Invoice i WHERE i.status = 'OPEN' AND i.nextPaymentAttemptAt <= :now ORDER BY i.nextPaymentAttemptAt ASC")
    List<Invoice> findDueForBilling(@Param("now") Instant now, Pageable pageable);
}
