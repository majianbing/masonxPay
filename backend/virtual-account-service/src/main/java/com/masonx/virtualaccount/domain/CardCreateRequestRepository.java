package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CardCreateRequestRepository {

    private final JdbcTemplate jdbc;

    public CardCreateRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<CardCreateRequest> find(String merchantId, Mode mode, String idempotencyKey) {
        var rows = jdbc.query("""
                SELECT * FROM card_create_request
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                  AND idempotency_key = ?
                """, ROW_MAPPER, merchantId, mode.name(), idempotencyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insertIfAbsent(CardCreateRequest request) {
        jdbc.update("""
                INSERT INTO card_create_request (
                    merchant_id, mode, idempotency_key, card_id, vcc_account_id, hold_account_id, status
                ) VALUES (
                    ?, ?::va_mode, ?, ?, ?, ?, ?
                )
                ON CONFLICT (merchant_id, mode, idempotency_key) DO NOTHING
                """,
                request.merchantId(),
                request.mode().name(),
                request.idempotencyKey(),
                request.cardId(),
                request.vccAccountId(),
                request.holdAccountId(),
                request.status());
    }

    public void markSucceeded(String merchantId, Mode mode, String idempotencyKey) {
        jdbc.update("""
                UPDATE card_create_request
                SET status = 'SUCCEEDED',
                    error_detail = NULL,
                    updated_at = now()
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                  AND idempotency_key = ?
                """, merchantId, mode.name(), idempotencyKey);
    }

    public void markFailed(String merchantId, Mode mode, String idempotencyKey, String errorDetail) {
        jdbc.update("""
                UPDATE card_create_request
                SET status = 'FAILED',
                    error_detail = ?,
                    updated_at = now()
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                  AND idempotency_key = ?
                """, errorDetail, merchantId, mode.name(), idempotencyKey);
    }

    private static final RowMapper<CardCreateRequest> ROW_MAPPER = (rs, __) -> new CardCreateRequest(
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("idempotency_key"),
            rs.getString("card_id"),
            rs.getString("vcc_account_id"),
            rs.getString("hold_account_id"),
            rs.getString("status"),
            rs.getString("error_detail"));

    public record CardCreateRequest(
            String merchantId,
            Mode mode,
            String idempotencyKey,
            String cardId,
            String vccAccountId,
            String holdAccountId,
            String status,
            String errorDetail
    ) {
    }
}
