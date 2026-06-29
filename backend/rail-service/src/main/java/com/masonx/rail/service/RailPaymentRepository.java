package com.masonx.rail.service;

import com.masonx.rail.canonical.CanonicalPaymentCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RailPaymentRepository {

    private final JdbcTemplate jdbc;

    public RailPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
