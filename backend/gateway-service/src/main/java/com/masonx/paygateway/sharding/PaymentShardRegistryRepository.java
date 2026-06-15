package com.masonx.paygateway.sharding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentShardRegistryRepository {

    private static final RowMapper<PaymentIdempotencyRoute> IDEMPOTENCY_ROUTE_MAPPER = (rs, rowNum) ->
            new PaymentIdempotencyRoute(
                    rs.getObject("merchant_id", UUID.class),
                    rs.getString("idempotency_key"),
                    rs.getObject("payment_intent_id", UUID.class),
                    rs.getInt("payment_shard_id"),
                    IdempotencyReservationStatus.valueOf(rs.getString("status"))
            );

    private static final RowMapper<PaymentRoute> PAYMENT_ROUTE_MAPPER = (rs, rowNum) ->
            new PaymentRoute(
                    rs.getObject("payment_intent_id", UUID.class),
                    rs.getInt("payment_shard_id")
            );

    private final JdbcTemplate jdbcTemplate;
    private final PaymentShardRouter router;

    public PaymentShardRegistryRepository(JdbcTemplate jdbcTemplate, PaymentShardRouter router) {
        this.jdbcTemplate = jdbcTemplate;
        this.router = router;
    }

    public Optional<PaymentIdempotencyRoute> findIdempotencyRoute(UUID merchantId, String idempotencyKey) {
        String table = router.idempotencyKeysTable(merchantId, idempotencyKey);
        List<PaymentIdempotencyRoute> routes = jdbcTemplate.query("""
                SELECT merchant_id, idempotency_key, payment_intent_id, payment_shard_id, status
                FROM %s
                WHERE merchant_id = ? AND idempotency_key = ?
                """.formatted(table), IDEMPOTENCY_ROUTE_MAPPER, merchantId, idempotencyKey);
        return routes.stream().findFirst();
    }

    public boolean reserveIdempotencyKey(UUID merchantId, String idempotencyKey,
                                         UUID paymentIntentId, int paymentShardId) {
        String table = router.idempotencyKeysTable(merchantId, idempotencyKey);
        int inserted = jdbcTemplate.update("""
                INSERT INTO %s (
                    merchant_id, idempotency_key, payment_intent_id, payment_shard_id, status
                )
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
                """.formatted(table),
                merchantId, idempotencyKey, paymentIntentId, paymentShardId,
                IdempotencyReservationStatus.IN_PROGRESS.name());
        return inserted == 1;
    }

    public void updateIdempotencyStatus(UUID merchantId, String idempotencyKey,
                                        IdempotencyReservationStatus status) {
        String table = router.idempotencyKeysTable(merchantId, idempotencyKey);
        jdbcTemplate.update("""
                UPDATE %s
                SET status = ?, updated_at = NOW()
                WHERE merchant_id = ? AND idempotency_key = ?
                """.formatted(table), status.name(), merchantId, idempotencyKey);
    }

    public Optional<PaymentRoute> findProviderPaymentRef(String provider, UUID connectorAccountId,
                                                         String providerPaymentId) {
        String table = router.providerPaymentRefsTable(provider, connectorAccountId, providerPaymentId);
        List<PaymentRoute> routes = jdbcTemplate.query("""
                SELECT payment_intent_id, payment_shard_id
                FROM %s
                WHERE provider = ? AND connector_account_id = ? AND provider_payment_id = ?
                """.formatted(table), PAYMENT_ROUTE_MAPPER,
                provider.toUpperCase(Locale.ROOT), connectorAccountId, providerPaymentId);
        return routes.stream().findFirst();
    }

    public boolean insertProviderPaymentRef(UUID merchantId, String provider, UUID connectorAccountId,
                                            String providerPaymentId, UUID paymentIntentId, int paymentShardId) {
        String table = router.providerPaymentRefsTable(provider, connectorAccountId, providerPaymentId);
        int inserted = jdbcTemplate.update("""
                INSERT INTO %s (
                    merchant_id, provider, connector_account_id, provider_payment_id,
                    payment_intent_id, payment_shard_id
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (provider, connector_account_id, provider_payment_id) DO NOTHING
                """.formatted(table),
                merchantId, provider.toUpperCase(Locale.ROOT), connectorAccountId, providerPaymentId,
                paymentIntentId, paymentShardId);
        return inserted == 1;
    }
}
