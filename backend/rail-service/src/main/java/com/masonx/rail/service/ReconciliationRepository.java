package com.masonx.rail.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ReconciliationRepository {

    private final JdbcTemplate jdbc;

    public ReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns all reconciliation exceptions, combining three sources:
     * <ol>
     *   <li>Late responses logged after reversal was already sent (MR2 scenario).
     *   <li>Reversal tasks that exhausted all retry attempts — require manual action.
     *   <li>Payments stuck in UNKNOWN or REVERSAL_REQUIRED — unresolved outcome.
     * </ol>
     */
    public List<ReconException> findExceptions(String merchantId) {
        List<ReconException> exceptions = new ArrayList<>();
        exceptions.addAll(findIso8583LogExceptions(merchantId));
        exceptions.addAll(findUnresolvedPayments(merchantId));
        return exceptions;
    }

    private List<ReconException> findIso8583LogExceptions(String merchantId) {
        return jdbc.query("""
                SELECT l.payment_id, l.network, l.mti AS exception_type, l.created_at
                FROM rail_iso8583_log l
                JOIN rail_payment p ON p.payment_id = l.payment_id
                WHERE l.mti IN ('LATE_RESPONSE_AFTER_REVERSAL', 'REVERSAL_EXHAUSTED', 'UNKNOWN_SEND_ERROR')
                  AND p.merchant_id = ?
                ORDER BY l.created_at DESC
                LIMIT 200
                """,
                (rs, row) -> new ReconException(
                        rs.getString("payment_id"),
                        rs.getString("exception_type"),
                        "ISO8583 log: network=" + rs.getString("network"),
                        rs.getTimestamp("created_at").toInstant()
                ),
                merchantId);
    }

    private List<ReconException> findUnresolvedPayments(String merchantId) {
        return jdbc.query("""
                SELECT payment_id, status::text AS exception_type, updated_at
                FROM rail_payment
                WHERE status IN ('UNKNOWN', 'REVERSAL_REQUIRED', 'RECON_EXCEPTION')
                  AND merchant_id = ?
                ORDER BY updated_at DESC
                LIMIT 200
                """,
                (rs, row) -> new ReconException(
                        rs.getString("payment_id"),
                        rs.getString("exception_type"),
                        "Payment stuck in unresolved state",
                        rs.getTimestamp("updated_at").toInstant()
                ),
                merchantId);
    }

    public record ReconException(
            String  paymentId,
            String  type,
            String  detail,
            Instant detectedAt
    ) {}
}
