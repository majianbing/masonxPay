package com.masonx.virtualaccount.domain;

import com.masonx.virtualaccount.domain.po.CardClearingEvent;
import com.masonx.common.tenant.Mode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class CardClearingEventRepository {

    private final JdbcTemplate jdbc;

    public CardClearingEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(CardClearingEvent event) {
        int rows = jdbc.update("""
                INSERT INTO card_clearing_event (
                    clearing_event_id, event_id, rail_payment_id, original_rail_payment_id,
                    issuer_id, original_authorization_id, movement_type,
                    card_id, auth_id, merchant_id, mode, amount, currency, status
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.clearingEventId(),
                event.eventId(),
                event.railPaymentId(),
                event.originalRailPaymentId(),
                event.issuerId(),
                event.originalAuthorizationId(),
                event.movementType(),
                event.cardId(),
                event.authId(),
                event.merchantId(),
                event.mode().name(),
                event.amount(),
                event.currency(),
                event.status());
        return rows == 1;
    }

    public Optional<CardClearingEvent> findMatchedByRailPaymentId(String railPaymentId) {
        var rows = jdbc.query("""
                SELECT * FROM card_clearing_event
                WHERE rail_payment_id = ?
                  AND status = 'MATCHED'
                ORDER BY created_at DESC
                LIMIT 1
                """, ROW_MAPPER, railPaymentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CardClearingEvent> findByRailPaymentId(String railPaymentId) {
        var rows = jdbc.query("""
                SELECT * FROM card_clearing_event
                WHERE rail_payment_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, ROW_MAPPER, railPaymentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CardClearingEvent> findMatchedByRailPaymentIdForUpdate(String railPaymentId) {
        var rows = jdbc.query("""
                SELECT * FROM card_clearing_event
                WHERE rail_payment_id = ?
                  AND status = 'MATCHED'
                ORDER BY created_at DESC
                LIMIT 1
                FOR UPDATE
                """, ROW_MAPPER, railPaymentId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public BigDecimal sumMatchedRefundAmountForOriginalRailPaymentId(String originalRailPaymentId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM card_clearing_event
                WHERE original_rail_payment_id = ?
                  AND movement_type = 'CARD_REFUND'
                  AND status = 'MATCHED'
                """, BigDecimal.class, originalRailPaymentId);
    }

    private static final RowMapper<CardClearingEvent> ROW_MAPPER = (rs, __) -> new CardClearingEvent(
            rs.getString("clearing_event_id"),
            rs.getString("event_id"),
            rs.getString("rail_payment_id"),
            rs.getString("original_rail_payment_id"),
            rs.getString("issuer_id"),
            rs.getString("original_authorization_id"),
            rs.getString("movement_type"),
            rs.getString("card_id"),
            rs.getString("auth_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());
}
