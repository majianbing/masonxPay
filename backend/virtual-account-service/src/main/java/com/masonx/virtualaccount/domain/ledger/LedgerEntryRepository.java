package com.masonx.virtualaccount.domain.ledger;

import com.masonx.virtualaccount.domain.Direction;
import com.masonx.virtualaccount.domain.EntryStatus;
import com.masonx.virtualaccount.domain.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
                    entry_seq, balance_after, balance_signature, source_event_id, status
                ) VALUES (
                    ?, ?, ?, ?::va_entry_direction, ?, ?,
                    ?, ?, ?, ?, ?::va_entry_status
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
                entry.balanceSignature(),
                entry.sourceEventId(),
                entry.status().name());
    }

    /**
     * Fetches the last entry for an account, ordered by entry_seq DESC.
     * Used to resolve the prev_seq and prev_signature for the HMAC chain.
     * Hits exactly one hash partition (account_id is the shard key).
     */
    public Optional<ChainAnchor> findLastAnchor(String accountId) {
        var rows = jdbc.query(
                """
                SELECT entry_seq, balance_signature
                FROM va_ledger_entry
                WHERE account_id = ?
                ORDER BY entry_seq DESC
                LIMIT 1
                """,
                (rs, __) -> new ChainAnchor(
                        rs.getLong("entry_seq"),
                        rs.getString("balance_signature")),
                accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
