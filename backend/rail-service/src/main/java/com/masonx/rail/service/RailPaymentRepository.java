package com.masonx.rail.service;

import com.masonx.rail.canonical.CanonicalPaymentCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RailPaymentRepository {

    private final JdbcTemplate jdbc;

    public RailPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Finds ACCEPTED bank payments that have a correlated {@code iso20022_end_to_end_id}
     * and have been in ACCEPTED state for at least 4 seconds (anti-thrash guard).
     * Limited to 50 rows per poll cycle.
     */
    public List<PendingBankPayment> findAcceptedBankPayments() {
        return jdbc.query("""
                SELECT rp.payment_id, rp.network, rnc.iso20022_end_to_end_id,
                       rnc.iso20022_message_id, rnc.correlation_key
                FROM rail_payment rp
                JOIN rail_network_correlation rnc ON rnc.payment_id = rp.payment_id
                WHERE rp.status = 'ACCEPTED'::rail_status
                  AND rp.rail   = 'BANK_ISO20022'::rail_type
                  AND rp.updated_at < now() - interval '4 seconds'
                  AND rnc.iso20022_end_to_end_id IS NOT NULL
                ORDER BY rp.updated_at ASC
                LIMIT 50
                """,
                (rs, row) -> new PendingBankPayment(
                        rs.getString("payment_id"),
                        rs.getString("network"),
                        rs.getString("iso20022_end_to_end_id"),
                        rs.getString("iso20022_message_id"),
                        rs.getString("correlation_key")
                ));
    }

    /** Projection used by the bank payment poller. */
    public record PendingBankPayment(
            String paymentId,
            String network,
            String endToEndId,
            String messageId,
            String correlationKey
    ) {}

    public void insert(CanonicalPaymentCommand cmd, String status) {
        jdbc.update("""
                INSERT INTO rail_payment
                    (payment_id, merchant_id, rail, network, movement_type,
                     amount, currency, status, idempotency_key, original_payment_id)
                VALUES (?, ?, ?::rail_type, ?, ?::rail_movement, ?, ?, ?::rail_status, ?, ?)
                """,
                cmd.paymentId(),
                cmd.merchantId(),
                cmd.rail().name(),
                cmd.metadata().getOrDefault("network", "UNKNOWN"),
                cmd.type().name(),
                cmd.amount(),
                cmd.currency(),
                status,
                cmd.idempotencyKey(),
                cmd.originalPaymentId());
    }

    public void updateStatus(String paymentId, String status) {
        jdbc.update(
                "UPDATE rail_payment SET status = ?::rail_status, updated_at = now(), version = version + 1 WHERE payment_id = ?",
                status, paymentId);
    }
}
