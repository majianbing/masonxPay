package com.masonx.virtualaccount.domain;

import com.masonx.virtualaccount.domain.constant.CardAuthorizationStatus;
import com.masonx.virtualaccount.domain.po.CardAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class CardAuthorizationRepository {

    private final JdbcTemplate jdbc;

    public CardAuthorizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insert(CardAuthorization auth) {
        int rows = jdbc.update("""
                INSERT INTO card_authorization (
                    auth_id, issuer_id, authorization_id, card_id, stan, rrn,
                    amount, currency, decision, decline_reason,
                    hold_event_id, status
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?::card_authorization_status
                )
                ON CONFLICT (issuer_id, authorization_id) DO NOTHING
                """,
                auth.authId(),
                auth.issuerId(),
                auth.authorizationId(),
                auth.cardId(),
                auth.stan(),
                auth.rrn(),
                auth.amount(),
                auth.currency(),
                auth.decision(),
                auth.declineReason(),
                auth.holdEventId(),
                auth.status().name());
        return rows == 1;
    }

    public void lockIdentity(String issuerId, String authorizationId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                rs -> null,
                issuerId,
                authorizationId);
    }

    public Optional<CardAuthorization> findByIssuerIdAndAuthorizationId(String issuerId, String authorizationId) {
        var rows = jdbc.query(
                "SELECT * FROM card_authorization WHERE issuer_id = ? AND authorization_id = ?",
                ROW_MAPPER, issuerId, authorizationId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CardAuthorization> findByIssuerIdAndAuthorizationIdForUpdate(String issuerId,
                                                                                 String authorizationId) {
        var rows = jdbc.query("""
                SELECT * FROM card_authorization
                WHERE issuer_id = ? AND authorization_id = ?
                FOR UPDATE
                """, ROW_MAPPER, issuerId, authorizationId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<CardAuthorization> findAuthorizedBeforeForUpdate(Instant before, int limit) {
        return jdbc.query("""
                SELECT * FROM card_authorization
                WHERE status = 'AUTHORIZED'
                  AND hold_event_id IS NOT NULL
                  AND created_at < ?
                ORDER BY created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, ROW_MAPPER, before, limit);
    }

    public Optional<CardAuthorization> findExactOpenHoldMatchForUpdate(String cardId, String currency,
                                                                       BigDecimal amount) {
        var rows = jdbc.query("""
                SELECT * FROM card_authorization
                WHERE card_id = ?
                  AND currency = ?
                  AND decision = 'APPROVED'
                  AND status = 'AUTHORIZED'
                  AND hold_event_id IS NOT NULL
                  AND amount - released_amount - settled_amount = ?
                ORDER BY created_at ASC
                LIMIT 1
                FOR UPDATE
                """, ROW_MAPPER, cardId, currency, amount);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CardAuthorization> findLinkedOpenHoldForUpdate(String issuerId, String authorizationId,
                                                                   String cardId) {
        var rows = jdbc.query("""
                SELECT * FROM card_authorization
                WHERE issuer_id = ?
                  AND authorization_id = ?
                  AND card_id = ?
                  AND decision = 'APPROVED'
                  AND status = 'AUTHORIZED'
                  AND hold_event_id IS NOT NULL
                  AND amount - released_amount - settled_amount > 0
                FOR UPDATE
                """, ROW_MAPPER, issuerId, authorizationId, cardId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean hasOpenHoldForCard(String cardId, String currency) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM card_authorization
                    WHERE card_id = ?
                      AND currency = ?
                      AND decision = 'APPROVED'
                      AND status = 'AUTHORIZED'
                      AND hold_event_id IS NOT NULL
                      AND amount - released_amount - settled_amount > 0
                )
                """, Boolean.class, cardId, currency);
        return Boolean.TRUE.equals(exists);
    }

    public void recordHoldRelease(String authId,
                                  BigDecimal releasedAmount,
                                  CardAuthorizationStatus status,
                                  String releaseReason,
                                  Instant releasedAt) {
        jdbc.update("""
                UPDATE card_authorization
                SET released_amount = ?,
                    status = ?::card_authorization_status,
                    release_reason = ?,
                    released_at = ?,
                    updated_at = now()
                WHERE auth_id = ?
                """, releasedAmount, status.name(), releaseReason, releasedAt, authId);
    }

    public void recordClearingSettlement(String authId,
                                         BigDecimal settledAmount,
                                         CardAuthorizationStatus status,
                                         Instant settledAt) {
        jdbc.update("""
                UPDATE card_authorization
                SET settled_amount = ?,
                    status = ?::card_authorization_status,
                    settled_at = ?,
                    updated_at = now()
                WHERE auth_id = ?
                """, settledAmount, status.name(), settledAt, authId);
    }

    public AuthorizationVelocity authorizedVelocitySince(String cardId, String currency, Instant since) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0) AS amount, COUNT(*) AS count
                FROM card_authorization
                WHERE card_id = ?
                  AND currency = ?
                  AND decision = 'APPROVED'
                  AND status = 'AUTHORIZED'
                  AND created_at >= ?
                """, (rs, __) -> new AuthorizationVelocity(
                rs.getBigDecimal("amount"),
                rs.getLong("count")), cardId, currency, since);
    }

    private static final RowMapper<CardAuthorization> ROW_MAPPER = (rs, __) -> new CardAuthorization(
            rs.getString("auth_id"),
            rs.getString("issuer_id"),
            rs.getString("authorization_id"),
            rs.getString("card_id"),
            rs.getString("stan"),
            rs.getString("rrn"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("decision"),
            rs.getString("decline_reason"),
            rs.getString("hold_event_id"),
            CardAuthorizationStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("released_amount"),
            rs.getString("release_reason"),
            rs.getTimestamp("released_at") != null ? rs.getTimestamp("released_at").toInstant() : null,
            rs.getBigDecimal("settled_amount"),
            rs.getTimestamp("settled_at") != null ? rs.getTimestamp("settled_at").toInstant() : null,
            rs.getTimestamp("created_at").toInstant());

    public record AuthorizationVelocity(BigDecimal amount, long count) {
    }
}
