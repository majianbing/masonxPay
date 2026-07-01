package com.masonx.paygateway.domain.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, UUID> {
    Optional<PaymentRequest> findByExternalId(String externalId);

    @Query("""
            select pr
            from PaymentRequest pr
            where pr.paymentIntentId = :paymentIntentId
            order by pr.attemptNumber asc, pr.createdAt asc
            """)
    List<PaymentRequest> findByPaymentIntentId(@Param("paymentIntentId") UUID paymentIntentId);

    /**
     * Computes per-connector-account success rates over a rolling window.
     * Returns rows of [connector_account_id (UUID), total (Long), succeeded (Long)].
     * Used by ConnectorHealthService (Phase 2.4) to feed Micrometer gauges.
     */
    @Query(value = """
            SELECT pr.connector_account_id,
                   COUNT(*)                                                            AS total,
                   SUM(CASE WHEN pr.status = 'SUCCEEDED' THEN 1 ELSE 0 END)           AS succeeded
            FROM payment_requests pr
            WHERE pr.created_at > :since
              AND pr.connector_account_id IS NOT NULL
            GROUP BY pr.connector_account_id
            """, nativeQuery = true)
    List<Object[]> computeConnectorSuccessRates(@Param("since") Instant since);
}
