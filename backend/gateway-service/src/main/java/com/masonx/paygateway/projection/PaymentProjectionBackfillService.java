package com.masonx.paygateway.projection;

import com.masonx.paygateway.domain.payment.PaymentIntentRepository;
import com.masonx.paygateway.domain.projection.PaymentReadModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.stream.IntStream;

@Service
public class PaymentProjectionBackfillService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PaymentProjectionBackfillService.class);
    private static final int PAYMENT_SHARD_COUNT = 64;

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentReadModelRepository readModelRepository;
    private final JdbcTemplate physicalJdbcTemplate;
    private final boolean enabled;

    public PaymentProjectionBackfillService(PaymentIntentRepository paymentIntentRepository,
                                            PaymentReadModelRepository readModelRepository,
                                            @Qualifier("flywayDataSource") DataSource physicalDataSource,
                                            @Value("${app.projection.backfill.enabled:false}") boolean enabled) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.readModelRepository = readModelRepository;
        this.physicalJdbcTemplate = new JdbcTemplate(physicalDataSource);
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            backfillOnStartup();
        } catch (Exception ex) {
            log.warn("Payment projection backfill failed: {}", ex.getMessage());
        }
    }

    void backfillOnStartup() {
        long sourceCount = paymentIntentRepository.count();
        long projectedCount = readModelRepository.count();
        if (projectedCount >= sourceCount) {
            log.info("Payment projection backfill skipped; read model has {} row(s) for {} payment intent(s)",
                    projectedCount, sourceCount);
            return;
        }
        log.info("Payment projection backfill started; read model has {} row(s) for {} payment intent(s)",
                projectedCount, sourceCount);
        int projected = physicalJdbcTemplate.update(backfillSql());
        log.info("Payment projection backfill upserted {} read model row(s)", projected);
    }

    public int triggerBackfill() {
        log.info("Payment projection backfill triggered on-demand");
        int upserted = physicalJdbcTemplate.update(backfillSql());
        log.info("Payment projection backfill upserted {} read model row(s)", upserted);
        return upserted;
    }

    private String backfillSql() {
        // Operational migration tradeoff: this backfill intentionally targets the physical
        // shard tables through the physical datasource. ShardingSphere rejects the logical
        // INSERT ... SELECT from sharded payment_intents into the single read-model table,
        // and full logical scans are too expensive for startup. Keep this out of request
        // paths and run it only when PAYMENT_PROJECTION_BACKFILL_ENABLED is explicitly set.
        String sourceIntents = IntStream.range(0, PAYMENT_SHARD_COUNT)
                .mapToObj(i -> "SELECT * FROM payment_intents_" + String.format("%02d", i))
                .reduce((left, right) -> left + "\nUNION ALL\n" + right)
                .orElseThrow();
        String sourceRequests = IntStream.range(0, PAYMENT_SHARD_COUNT)
                .mapToObj(i -> "SELECT payment_intent_id, payment_method_type, created_at FROM payment_requests_" + String.format("%02d", i))
                .reduce((left, right) -> left + "\nUNION ALL\n" + right)
                .orElseThrow();
        return """
                WITH source_intents AS (
                %s
                ),
                all_requests AS (
                %s
                ),
                latest_method AS (
                    SELECT DISTINCT ON (payment_intent_id)
                        payment_intent_id,
                        payment_method_type
                    FROM all_requests
                    ORDER BY payment_intent_id, created_at DESC
                )
                INSERT INTO payment_read_models (
                    payment_intent_id,
                    external_id,
                    merchant_id,
                    mode,
                    amount,
                    currency,
                    status,
                    capture_method,
                    resolved_provider,
                    connector_account_id,
                    provider_payment_id,
                    idempotency_key,
                    order_id,
                    description,
                    refunded_amount_succeeded,
                    payment_method_type,
                    search_text,
                    source_created_at,
                    source_updated_at
                )
                SELECT
                    pi.id,
                    pi.external_id,
                    pi.merchant_id,
                    pi.mode,
                    pi.amount,
                    pi.currency,
                    pi.status,
                    pi.capture_method,
                    pi.resolved_provider,
                    pi.connector_account_id,
                    pi.provider_payment_id,
                    pi.idempotency_key,
                    pi.order_id,
                    pi.description,
                    COALESCE(r.refunded_amount_succeeded, 0),
                    lm.payment_method_type,
                    lower(concat_ws(' ',
                        pi.id::text,
                        pi.external_id,
                        pi.provider_payment_id,
                        pi.order_id,
                        pi.description,
                        pi.resolved_provider
                    )),
                    pi.created_at,
                    pi.updated_at
                FROM source_intents pi
                LEFT JOIN (
                    SELECT payment_intent_id, SUM(amount) AS refunded_amount_succeeded
                    FROM refunds
                    WHERE status = 'SUCCEEDED'
                    GROUP BY payment_intent_id
                ) r ON r.payment_intent_id = pi.id
                LEFT JOIN latest_method lm ON lm.payment_intent_id = pi.id
                ON CONFLICT (payment_intent_id) DO UPDATE SET
                    external_id = EXCLUDED.external_id,
                    merchant_id = EXCLUDED.merchant_id,
                    mode = EXCLUDED.mode,
                    amount = EXCLUDED.amount,
                    currency = EXCLUDED.currency,
                    status = EXCLUDED.status,
                    capture_method = EXCLUDED.capture_method,
                    resolved_provider = EXCLUDED.resolved_provider,
                    connector_account_id = EXCLUDED.connector_account_id,
                    provider_payment_id = EXCLUDED.provider_payment_id,
                    idempotency_key = EXCLUDED.idempotency_key,
                    order_id = EXCLUDED.order_id,
                    description = EXCLUDED.description,
                    refunded_amount_succeeded = EXCLUDED.refunded_amount_succeeded,
                    payment_method_type = COALESCE(EXCLUDED.payment_method_type, payment_read_models.payment_method_type),
                    search_text = EXCLUDED.search_text,
                    source_created_at = EXCLUDED.source_created_at,
                    source_updated_at = EXCLUDED.source_updated_at,
                    updated_at = now()
                """.formatted(sourceIntents, sourceRequests);
    }
}
