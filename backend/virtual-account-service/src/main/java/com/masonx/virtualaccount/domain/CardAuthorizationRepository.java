package com.masonx.virtualaccount.domain;

import com.masonx.virtualaccount.domain.constant.CardAuthorizationStatus;
import com.masonx.virtualaccount.domain.po.CardAuthorization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
            rs.getTimestamp("created_at").toInstant());
}
