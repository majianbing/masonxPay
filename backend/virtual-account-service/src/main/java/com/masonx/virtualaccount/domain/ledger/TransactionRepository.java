package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.constant.TransactionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PostTransaction tx) {
        jdbc.update("""
                INSERT INTO va_transaction (
                    transaction_id, entry_type, description, payment_reference_id,
                    effective_date, mode, org_id, merchant_id
                ) VALUES (
                    ?, ?, ?, ?,
                    ?, ?::va_mode, ?, ?
                )
                """,
                tx.transactionId(),
                tx.entryType().name(),
                tx.description(),
                tx.paymentReferenceId(),
                tx.effectiveDate(),
                tx.mode().name(),
                tx.orgId(),
                tx.merchantId());
    }

    public Optional<TransactionRecord> findById(String transactionId) {
        var rows = jdbc.query(
                "SELECT * FROM va_transaction WHERE transaction_id = ?",
                ROW_MAPPER, transactionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static final RowMapper<TransactionRecord> ROW_MAPPER = (rs, __) -> {
        var mode = com.masonx.common.tenant.Mode.valueOf(rs.getString("mode"));
        var entryType = TransactionType.valueOf(rs.getString("entry_type"));
        LocalDate effectiveDate = rs.getObject("effective_date", LocalDate.class);
        return new TransactionRecord(
                rs.getString("transaction_id"),
                entryType,
                rs.getString("description"),
                rs.getString("payment_reference_id"),
                effectiveDate,
                rs.getString("status"),
                mode,
                rs.getString("org_id"),
                rs.getString("merchant_id"),
                rs.getTimestamp("created_at").toInstant());
    };
}
