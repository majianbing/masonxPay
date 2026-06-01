package com.masonx.paygateway.domain.billing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
     * Finds OPEN invoices whose next payment attempt is due.
     * Plain SELECT — no locking hint, compatible with ShardingSphere.
     * Mutual exclusion is handled by claimForBilling() optimistic update.
     */
    @Query("SELECT i FROM Invoice i WHERE i.status = 'OPEN' AND i.nextPaymentAttemptAt <= :now ORDER BY i.nextPaymentAttemptAt ASC")
    List<Invoice> findDueForBilling(@Param("now") Instant now, Pageable pageable);

    /**
     * Optimistic claim: sets nextPaymentAttemptAt to the claim window only if the row
     * still has the expected timestamp and status. Returns 1 if claimed, 0 if another
     * worker already claimed it (timestamp changed) or the invoice was already paid.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Invoice i SET i.nextPaymentAttemptAt = :claimUntil " +
           "WHERE i.id = :id " +
           "AND i.status = com.masonx.paygateway.domain.billing.InvoiceStatus.OPEN " +
           "AND i.nextPaymentAttemptAt = :expectedAt")
    int claimForBilling(@Param("id") UUID id,
                        @Param("expectedAt") Instant expectedAt,
                        @Param("claimUntil") Instant claimUntil);
}
