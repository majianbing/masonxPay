package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CardIssuerReconciliationRepository {

    private final JdbcTemplate jdbc;

    public CardIssuerReconciliationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertOpen(CardIssuerReconciliationTask task) {
        jdbc.update("""
                INSERT INTO card_issuer_reconciliation_task (
                    task_id, merchant_id, mode, card_id, issuer_partner_id, external_issuer_card_id,
                    requested_action, target_status, reason, idempotency_key, status, error_detail
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?
                )
                ON CONFLICT (card_id, requested_action, idempotency_key) DO UPDATE SET
                    status = 'OPEN',
                    target_status = EXCLUDED.target_status,
                    reason = EXCLUDED.reason,
                    error_detail = EXCLUDED.error_detail,
                    updated_at = now()
                """,
                task.taskId(),
                task.merchantId(),
                task.mode().name(),
                task.cardId(),
                task.issuerPartnerId(),
                task.externalIssuerCardId(),
                task.requestedAction(),
                task.targetStatus(),
                task.reason(),
                task.idempotencyKey(),
                task.errorDetail());
    }

    public record CardIssuerReconciliationTask(
            String taskId,
            String merchantId,
            Mode mode,
            String cardId,
            String issuerPartnerId,
            String externalIssuerCardId,
            String requestedAction,
            String targetStatus,
            String reason,
            String idempotencyKey,
            String errorDetail
    ) {
    }
}
