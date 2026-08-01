package com.masonx.paygateway.fee;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.PaymentProvider;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GatewayFeeAssessmentRepository {

    private final JdbcTemplate jdbc;

    public GatewayFeeAssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GatewayFeeAssessmentSnapshot> findByEvent(UUID merchantId,
                                                              ApiKeyMode mode,
                                                              String eventType,
                                                              UUID eventId) {
        List<GatewayFeeAssessment> assessments = jdbc.query("""
                SELECT * FROM gateway_fee_assessment
                WHERE merchant_id = ?
                  AND mode = ?
                  AND event_type = ?
                  AND event_id = ?
                """, ASSESSMENT_MAPPER, merchantId, mode.name(), eventType, eventId);
        if (assessments.isEmpty()) {
            return Optional.empty();
        }
        GatewayFeeAssessment assessment = assessments.get(0);
        return Optional.of(new GatewayFeeAssessmentSnapshot(assessment, findLines(assessment.assessmentId())));
    }

    public boolean saveSnapshotIfAbsent(GatewayFeeAssessment assessment, List<GatewayFeeAssessmentLine> lines) {
        int inserted = jdbc.update("""
                INSERT INTO gateway_fee_assessment (
                    assessment_id, merchant_id, mode, event_type, event_id,
                    payment_intent_id, payment_request_id, provider, connector_account_id,
                    payment_method_type, schedule_id, schedule_version, context_json,
                    matched_rules_json, visible_totals_json, hidden_totals_json
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?, ?::jsonb,
                    ?::jsonb, ?::jsonb, ?::jsonb
                )
                ON CONFLICT (merchant_id, mode, event_type, event_id) DO NOTHING
                """,
                assessment.assessmentId(),
                assessment.merchantId(),
                assessment.mode().name(),
                assessment.eventType(),
                assessment.eventId(),
                assessment.paymentIntentId(),
                assessment.paymentRequestId(),
                assessment.provider() != null ? assessment.provider().name() : null,
                assessment.connectorAccountId(),
                assessment.paymentMethodType(),
                assessment.scheduleId(),
                assessment.scheduleVersion(),
                assessment.contextJson(),
                assessment.matchedRulesJson(),
                assessment.visibleTotalsJson(),
                assessment.hiddenTotalsJson());
        if (inserted == 0) {
            return false;
        }
        saveLines(lines);
        return true;
    }

    private List<GatewayFeeAssessmentLine> findLines(String assessmentId) {
        return jdbc.query("""
                SELECT * FROM gateway_fee_assessment_line
                WHERE assessment_id = ?
                ORDER BY line_id ASC
                """, LINE_MAPPER, assessmentId);
    }

    private void saveLines(List<GatewayFeeAssessmentLine> lines) {
        jdbc.batchUpdate("""
                INSERT INTO gateway_fee_assessment_line (
                    assessment_id, merchant_id, mode, rule_id, rule_version, rule_name, component_id, name,
                    visibility, currency, basis_amount, raw_calculated_amount, amount,
                    rounding_mode, rounding_scale, metadata_json
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?::jsonb
                )
                """, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        GatewayFeeAssessmentLine line = lines.get(i);
                        ps.setString(1, line.assessmentId());
                        ps.setObject(2, line.merchantId());
                        ps.setString(3, line.mode().name());
                        ps.setString(4, line.ruleId());
                        ps.setInt(5, line.ruleVersion());
                        ps.setString(6, line.ruleName());
                        ps.setString(7, line.componentId());
                        ps.setString(8, line.name());
                        ps.setString(9, line.visibility());
                        ps.setString(10, line.currency());
                        ps.setBigDecimal(11, line.basisAmount());
                        ps.setBigDecimal(12, line.rawCalculatedAmount());
                        ps.setBigDecimal(13, line.amount());
                        ps.setString(14, line.roundingMode());
                        ps.setInt(15, line.roundingScale());
                        ps.setString(16, line.metadataJson());
                    }

                    @Override
                    public int getBatchSize() {
                        return lines.size();
                    }
                });
    }

    private static final RowMapper<GatewayFeeAssessment> ASSESSMENT_MAPPER = (rs, __) ->
            new GatewayFeeAssessment(
                    rs.getString("assessment_id"),
                    rs.getObject("merchant_id", UUID.class),
                    ApiKeyMode.valueOf(rs.getString("mode")),
                    rs.getString("event_type"),
                    rs.getObject("event_id", UUID.class),
                    rs.getObject("payment_intent_id", UUID.class),
                    rs.getObject("payment_request_id", UUID.class),
                    rs.getString("provider") != null ? PaymentProvider.valueOf(rs.getString("provider")) : null,
                    rs.getObject("connector_account_id", UUID.class),
                    rs.getString("payment_method_type"),
                    rs.getString("schedule_id"),
                    rs.getInt("schedule_version"),
                    rs.getString("context_json"),
                    rs.getString("matched_rules_json"),
                    rs.getString("visible_totals_json"),
                    rs.getString("hidden_totals_json"),
                    rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<GatewayFeeAssessmentLine> LINE_MAPPER = (rs, __) ->
            new GatewayFeeAssessmentLine(
                    rs.getLong("line_id"),
                    rs.getString("assessment_id"),
                    rs.getObject("merchant_id", UUID.class),
                    ApiKeyMode.valueOf(rs.getString("mode")),
                    rs.getString("rule_id"),
                    rs.getInt("rule_version"),
                    rs.getString("rule_name"),
                    rs.getString("component_id"),
                    rs.getString("name"),
                    rs.getString("visibility"),
                    rs.getString("currency"),
                    rs.getBigDecimal("basis_amount"),
                    rs.getBigDecimal("raw_calculated_amount"),
                    rs.getBigDecimal("amount"),
                    rs.getString("rounding_mode"),
                    rs.getInt("rounding_scale"),
                    rs.getString("metadata_json"),
                    rs.getTimestamp("created_at").toInstant());
}
