package com.masonx.rail.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class ReversalTaskRepository {

    private final JdbcTemplate jdbc;

    public ReversalTaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String id, String paymentId, String network,
                       String originalStan, String originalRrn, Instant originalTxTime) {
        jdbc.update("""
                INSERT INTO rail_reversal_task
                    (id, payment_id, status, attempts, max_attempts,
                     network, original_stan, original_rrn, original_tx_time)
                VALUES (?, ?, 'PENDING'::reversal_status, 0, 3, ?, ?, ?, ?)
                """,
                id, paymentId, network, originalStan, originalRrn,
                originalTxTime != null ? Timestamp.from(originalTxTime) : null);
    }

    /**
     * Finds PENDING due tasks, atomically marks them SENT, and returns them for processing.
     *
     * <p>Uses {@code FOR UPDATE SKIP LOCKED} so concurrent scheduler instances cannot
     * double-process the same task.
     *
     * <p>The task is joined with {@code rail_payment} so callers have everything needed
     * to build the 0400 message without a second query.
     */
    @Transactional
    public List<ReversalTask> findAndClaimDueTasks() {
        List<ReversalTask> tasks = jdbc.query("""
                SELECT rt.id, rt.payment_id, rt.attempts, rt.max_attempts,
                       rt.original_stan, rt.original_rrn, rt.network, rt.original_tx_time,
                       rp.amount, rp.currency, rp.merchant_id
                FROM rail_reversal_task rt
                JOIN rail_payment rp ON rt.payment_id = rp.payment_id
                WHERE rt.status = 'PENDING'
                  AND (rt.next_attempt_at IS NULL OR rt.next_attempt_at <= now())
                ORDER BY rt.created_at
                LIMIT 10
                FOR UPDATE OF rt SKIP LOCKED
                """,
                (rs, row) -> new ReversalTask(
                        rs.getString("id"),
                        rs.getString("payment_id"),
                        rs.getInt("attempts"),
                        rs.getInt("max_attempts"),
                        rs.getString("original_stan"),
                        rs.getString("original_rrn"),
                        rs.getString("network"),
                        toInstant(rs.getTimestamp("original_tx_time")),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency"),
                        rs.getString("merchant_id")
                ));

        // Increment attempts and mark SENT atomically before releasing the DB lock.
        for (ReversalTask t : tasks) {
            jdbc.update("""
                    UPDATE rail_reversal_task
                       SET status = 'SENT'::reversal_status,
                           attempts = attempts + 1,
                           updated_at = now()
                     WHERE id = ?
                    """, t.id());
        }
        return tasks;
    }

    public void markResolved(String taskId) {
        jdbc.update("""
                UPDATE rail_reversal_task
                   SET status = 'RESOLVED'::reversal_status,
                       resolved_at = now(),
                       updated_at = now()
                 WHERE id = ?
                """, taskId);
    }

    /** Re-queues a task after a failed 0410 attempt. Backoff delay is in seconds. */
    public void requeueTask(String taskId, long backoffSeconds) {
        jdbc.update("""
                UPDATE rail_reversal_task
                   SET status = 'PENDING'::reversal_status,
                       next_attempt_at = now() + (? * interval '1 second'),
                       updated_at = now()
                 WHERE id = ?
                """, backoffSeconds, taskId);
    }

    public void exhaustTask(String taskId) {
        jdbc.update("""
                UPDATE rail_reversal_task
                   SET status = 'EXHAUSTED'::reversal_status,
                       updated_at = now()
                 WHERE id = ?
                """, taskId);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
