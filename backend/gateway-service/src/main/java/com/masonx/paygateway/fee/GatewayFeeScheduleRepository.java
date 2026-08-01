package com.masonx.paygateway.fee;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GatewayFeeScheduleRepository {

    private final JdbcTemplate jdbc;

    public GatewayFeeScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GatewayFeeScheduleVersion> findActiveVersion(UUID merchantId,
                                                                 ApiKeyMode mode,
                                                                 String eventType,
                                                                 PaymentProvider provider,
                                                                 UUID connectorAccountId,
                                                                 String paymentMethodType,
                                                                 Instant asOf) {
        List<GatewayFeeScheduleVersion> rows = jdbc.query("""
                SELECT v.*
                FROM gateway_fee_schedule_version v
                JOIN gateway_fee_schedule s
                  ON s.schedule_id = v.schedule_id
                 AND s.merchant_id = v.merchant_id
                 AND s.mode = v.mode
                WHERE s.merchant_id = ?
                  AND s.mode = ?
                  AND s.status = 'ACTIVE'
                  AND v.status = 'ACTIVE'
                  AND v.effective_from <= ?
                  AND (v.effective_to IS NULL OR v.effective_to > ?)
                  AND (s.event_type = ? OR s.event_type IS NULL)
                  AND (s.provider = ? OR s.provider IS NULL)
                  AND (s.connector_account_id = ? OR s.connector_account_id IS NULL)
                  AND (s.payment_method_type = ? OR s.payment_method_type IS NULL)
                ORDER BY
                  CASE WHEN s.connector_account_id = ? THEN 0 ELSE 1 END,
                  CASE WHEN s.provider = ? THEN 0 ELSE 1 END,
                  CASE WHEN s.payment_method_type = ? THEN 0 ELSE 1 END,
                  CASE WHEN s.event_type = ? THEN 0 ELSE 1 END,
                  v.effective_from DESC,
                  v.version DESC
                LIMIT 1
                """,
                VERSION_MAPPER,
                merchantId,
                mode.name(),
                Timestamp.from(asOf),
                Timestamp.from(asOf),
                eventType,
                provider != null ? provider.name() : null,
                connectorAccountId,
                paymentMethodType,
                connectorAccountId,
                provider != null ? provider.name() : null,
                paymentMethodType,
                eventType);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static final RowMapper<GatewayFeeScheduleVersion> VERSION_MAPPER = (rs, __) ->
            new GatewayFeeScheduleVersion(
                    rs.getString("schedule_id"),
                    rs.getObject("merchant_id", UUID.class),
                    ApiKeyMode.valueOf(rs.getString("mode")),
                    rs.getInt("version"),
                    GatewayFeeScheduleStatus.valueOf(rs.getString("status")),
                    rs.getString("fee_currency"),
                    rs.getInt("fee_scale"),
                    rs.getString("rounding_mode"),
                    rs.getTimestamp("effective_from").toInstant(),
                    rs.getTimestamp("effective_to") != null
                            ? rs.getTimestamp("effective_to").toInstant()
                            : null,
                    rs.getString("rules_json"),
                    rs.getString("metadata_json"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("published_at") != null
                            ? rs.getTimestamp("published_at").toInstant()
                            : null);
}
