package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.constant.Direction;
import com.masonx.virtualaccount.domain.constant.EntryStatus;
import com.masonx.virtualaccount.domain.po.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class LedgerEntryRepository {

    private final JdbcTemplate jdbc;

    public LedgerEntryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(LedgerEntry entry) {
        jdbc.update("""
                INSERT INTO va_ledger_entry (
                    entry_id, transaction_id, account_id, direction, amount, asset,
                    entry_seq, balance_after, frozen_balance, prev_signature,
                    balance_signature, source_event_id, status, effective_date
                ) VALUES (
                    ?, ?, ?, ?::va_entry_direction, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?::va_entry_status, ?
                )
                """,
                entry.entryId(),
                entry.transactionId(),
                entry.accountId(),
                entry.direction().name(),
                entry.amount(),
                entry.asset(),
                entry.entrySeq(),
                entry.balanceAfter(),
                entry.frozenBalance(),
                entry.prevSignature(),
                entry.balanceSignature(),
                entry.sourceEventId(),
                entry.status().name(),
                entry.effectiveDate());
    }

    /**
     * Σ(DEBIT amounts) − Σ(CREDIT amounts) for POSTED entries with effective_date < beforeDate.
     * One partition hit (account_id shard key). Caller applies normalBalance sign:
     *   balance = DEBIT-normal ? debitNet : −debitNet
     */
    public BigDecimal sumDebitNetBeforeDate(String accountId, LocalDate beforeDate) {
        BigDecimal result = jdbc.queryForObject("""
                SELECT COALESCE(
                    SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0
                ) FROM va_ledger_entry
                WHERE account_id = ? AND status = 'POSTED' AND effective_date < ?
                """, BigDecimal.class, accountId, beforeDate);
        return result != null ? result : BigDecimal.ZERO;
    }

    /** Same for effective_date <= toDate. */
    public BigDecimal sumDebitNetUpToDate(String accountId, LocalDate toDate) {
        BigDecimal result = jdbc.queryForObject("""
                SELECT COALESCE(
                    SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0
                ) FROM va_ledger_entry
                WHERE account_id = ? AND status = 'POSTED' AND effective_date <= ?
                """, BigDecimal.class, accountId, toDate);
        return result != null ? result : BigDecimal.ZERO;
    }

    /** Period entries [fromDate, toDate] ordered entry_seq ASC. One partition hit. */
    public List<LedgerEntry> findByAccountIdAndEffectiveDateRange(
            String accountId, LocalDate fromDate, LocalDate toDate) {
        return jdbc.query("""
                SELECT * FROM va_ledger_entry
                WHERE account_id = ? AND effective_date BETWEEN ? AND ?
                ORDER BY entry_seq ASC
                """, ENTRY_ROW_MAPPER, accountId, fromDate, toDate);
    }

    /** Paginated entry list for an account, newest first. One partition hit. */
    public List<LedgerEntry> findByAccountId(String accountId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM va_ledger_entry
                WHERE account_id = ?
                ORDER BY entry_seq DESC
                LIMIT ? OFFSET ?
                """, ENTRY_ROW_MAPPER, accountId, size, (long) page * size);
    }

    public long countByAccountId(String accountId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM va_ledger_entry WHERE account_id = ?",
                Long.class, accountId);
        return n != null ? n : 0L;
    }

    /**
     * Fetches all entries for a transaction across all 64 va_ledger_entry partitions
     * (fan-out; per-partition transaction_id index reduces cost within each shard).
     * Acceptable for audit path only — not a throughput-sensitive caller.
     */
    public List<LedgerEntry> findByTransactionId(String transactionId) {
        return jdbc.query("""
                SELECT * FROM va_ledger_entry
                WHERE transaction_id = ?
                ORDER BY entry_seq ASC
                """, ENTRY_ROW_MAPPER, transactionId);
    }

    private static final RowMapper<LedgerEntry> ENTRY_ROW_MAPPER = (rs, __) -> new LedgerEntry(
            rs.getString("entry_id"),
            rs.getString("transaction_id"),
            rs.getString("account_id"),
            Direction.valueOf(rs.getString("direction")),
            rs.getBigDecimal("amount"),
            rs.getString("asset"),
            rs.getLong("entry_seq"),
            rs.getBigDecimal("balance_after"),
            rs.getBigDecimal("frozen_balance"),
            rs.getString("prev_signature"),
            rs.getString("balance_signature"),
            rs.getString("source_event_id"),
            EntryStatus.valueOf(rs.getString("status")),
            rs.getObject("effective_date", LocalDate.class),
            rs.getTimestamp("created_at").toInstant());

    /**
     * Fetches the last entry for an account with all fields needed to verify its
     * own signature and derive the anchor for the next entry.
     * Hits exactly one hash partition (account_id is the shard key).
     * Must be called inside the posting transaction after SELECT FOR UPDATE.
     */
    public Optional<ChainHead> findLastChainHead(String accountId) {
        var rows = jdbc.query(
                """
                SELECT entry_seq, amount, direction, balance_after,
                       frozen_balance, transaction_id, prev_signature, balance_signature
                FROM va_ledger_entry
                WHERE account_id = ?
                ORDER BY entry_seq DESC
                LIMIT 1
                """,
                (rs, __) -> new ChainHead(
                        rs.getLong("entry_seq"),
                        rs.getBigDecimal("amount"),
                        Direction.valueOf(rs.getString("direction")),
                        rs.getBigDecimal("balance_after"),
                        rs.getBigDecimal("frozen_balance"),
                        rs.getString("transaction_id"),
                        rs.getString("prev_signature"),
                        rs.getString("balance_signature")),
                accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
