package com.masonx.virtualaccount.domain;

import com.masonx.virtualaccount.domain.constant.SettlementExceptionReason;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionSource;
import com.masonx.virtualaccount.domain.constant.SettlementExceptionStatus;
import com.masonx.virtualaccount.domain.po.SettlementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SettlementExceptionRepository {

    private final JdbcTemplate jdbc;

    public SettlementExceptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Parks (or re-parks) an event. UNIQUE(event_id) makes redelivery of the same
     * failing event bump delivery_count on the existing row — and reopen it if it
     * was DISCARDED, because an event that keeps arriving must stay visible.
     */
    public void upsertOpen(String exceptionId, SettlementExceptionSource source,
                           String eventId, String eventType,
                           SettlementExceptionReason reason, String errorDetail,
                           String payloadJson) {
        jdbc.update("""
                INSERT INTO settlement_exception (
                    exception_id, source, event_id, event_type, payload,
                    reason_code, error_detail, status
                ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 'OPEN')
                ON CONFLICT (event_id) DO UPDATE SET
                    reason_code    = EXCLUDED.reason_code,
                    error_detail   = EXCLUDED.error_detail,
                    payload        = EXCLUDED.payload,
                    status         = 'OPEN',
                    delivery_count = settlement_exception.delivery_count + 1,
                    updated_at     = now()
                WHERE settlement_exception.status != 'RESOLVED'
                """,
                exceptionId, source.name(), eventId, eventType,
                payloadJson, reason.name(), errorDetail);
    }

    public Optional<SettlementException> findById(String exceptionId) {
        var rows = jdbc.query(
                "SELECT * FROM settlement_exception WHERE exception_id = ?",
                ROW_MAPPER, exceptionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<SettlementException> findByEventId(String eventId) {
        var rows = jdbc.query(
                "SELECT * FROM settlement_exception WHERE event_id = ?",
                ROW_MAPPER, eventId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<SettlementException> findPage(SettlementExceptionStatus statusOrNull, int page, int size) {
        if (statusOrNull == null) {
            return jdbc.query("""
                    SELECT * FROM settlement_exception
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                    """, ROW_MAPPER, size, (long) page * size);
        }
        return jdbc.query("""
                SELECT * FROM settlement_exception
                WHERE status = ?::settlement_exception_status
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, statusOrNull.name(), size, (long) page * size);
    }

    public long count(SettlementExceptionStatus statusOrNull) {
        Long n = statusOrNull == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM settlement_exception", Long.class)
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM settlement_exception WHERE status = ?::settlement_exception_status",
                        Long.class, statusOrNull.name());
        return n != null ? n : 0L;
    }

    public void markResolved(String exceptionId, String note) {
        jdbc.update("""
                UPDATE settlement_exception
                SET status = 'RESOLVED', resolution_note = ?, updated_at = now()
                WHERE exception_id = ?
                """, note, exceptionId);
    }

    public void markDiscarded(String exceptionId, String note) {
        jdbc.update("""
                UPDATE settlement_exception
                SET status = 'DISCARDED', resolution_note = ?, updated_at = now()
                WHERE exception_id = ?
                """, note, exceptionId);
    }

    public void incrementRetryCount(String exceptionId) {
        jdbc.update("""
                UPDATE settlement_exception
                SET retry_count = retry_count + 1, updated_at = now()
                WHERE exception_id = ?
                """, exceptionId);
    }

    private static final RowMapper<SettlementException> ROW_MAPPER = (rs, __) -> new SettlementException(
            rs.getString("exception_id"),
            SettlementExceptionSource.valueOf(rs.getString("source")),
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("payload"),
            SettlementExceptionReason.valueOf(rs.getString("reason_code")),
            rs.getString("error_detail"),
            SettlementExceptionStatus.valueOf(rs.getString("status")),
            rs.getInt("delivery_count"),
            rs.getInt("retry_count"),
            rs.getString("resolution_note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
}
