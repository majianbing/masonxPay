package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessment;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentLine;
import com.masonx.virtualaccount.domain.po.PrepaidFeeAssessmentSnapshot;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class PrepaidFeeAssessmentRepository {

    private final JdbcTemplate jdbc;

    public PrepaidFeeAssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PrepaidFeeAssessmentSnapshot> findByEvent(String merchantId, Mode mode, String eventType, String eventId) {
        List<PrepaidFeeAssessment> assessments = jdbc.query("""
                SELECT * FROM prepaid_fee_assessment
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                  AND event_type = ?
                  AND event_id = ?
                """, ASSESSMENT_MAPPER, merchantId, mode.name(), eventType, eventId);
        if (assessments.isEmpty()) {
            return Optional.empty();
        }
        PrepaidFeeAssessment assessment = assessments.get(0);
        return Optional.of(new PrepaidFeeAssessmentSnapshot(assessment, findLines(assessment.assessmentId())));
    }

    public List<PrepaidFeeAssessmentSnapshot> listForMerchant(String merchantId, Mode mode, int page, int size) {
        List<PrepaidFeeAssessment> assessments = jdbc.query("""
                SELECT * FROM prepaid_fee_assessment
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, ASSESSMENT_MAPPER, merchantId, mode.name(), size, (long) page * size);
        return assessments.stream()
                .map(assessment -> new PrepaidFeeAssessmentSnapshot(
                        assessment, findLines(assessment.assessmentId())))
                .toList();
    }

    public long countForMerchant(String merchantId, Mode mode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM prepaid_fee_assessment
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                """, Long.class, merchantId, mode.name());
        return count != null ? count : 0L;
    }

    public boolean saveSnapshotIfAbsent(PrepaidFeeAssessment assessment, List<PrepaidFeeAssessmentLine> lines) {
        int inserted = jdbc.update("""
                INSERT INTO prepaid_fee_assessment (
                    assessment_id, merchant_id, mode, event_type, event_id, program_id, card_id,
                    schedule_id, schedule_version, context_json, matched_rules_json,
                    visible_totals_json, hidden_totals_json
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?, ?, ?,
                    ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb
                )
                ON CONFLICT (merchant_id, mode, event_type, event_id) DO NOTHING
                """,
                assessment.assessmentId(),
                assessment.merchantId(),
                assessment.mode().name(),
                assessment.eventType(),
                assessment.eventId(),
                assessment.programId(),
                assessment.cardId(),
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

    private List<PrepaidFeeAssessmentLine> findLines(String assessmentId) {
        return jdbc.query("""
                SELECT * FROM prepaid_fee_assessment_line
                WHERE assessment_id = ?
                ORDER BY line_id ASC
                """, LINE_MAPPER, assessmentId);
    }

    private void saveLines(List<PrepaidFeeAssessmentLine> lines) {
        jdbc.batchUpdate("""
                INSERT INTO prepaid_fee_assessment_line (
                    assessment_id, merchant_id, mode, rule_id, rule_version, rule_name, component_id, name,
                    visibility, currency, basis_amount, raw_calculated_amount, amount,
                    rounding_mode, rounding_scale, metadata_json
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?::jsonb
                )
                """, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        PrepaidFeeAssessmentLine line = lines.get(i);
                        ps.setString(1, line.assessmentId());
                        ps.setString(2, line.merchantId());
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

    private static final RowMapper<PrepaidFeeAssessment> ASSESSMENT_MAPPER = (rs, __) -> new PrepaidFeeAssessment(
            rs.getString("assessment_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("event_type"),
            rs.getString("event_id"),
            rs.getString("program_id"),
            rs.getString("card_id"),
            rs.getString("schedule_id"),
            rs.getInt("schedule_version"),
            rs.getString("context_json"),
            rs.getString("matched_rules_json"),
            rs.getString("visible_totals_json"),
            rs.getString("hidden_totals_json"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<PrepaidFeeAssessmentLine> LINE_MAPPER = (rs, __) -> new PrepaidFeeAssessmentLine(
            rs.getLong("line_id"),
            rs.getString("assessment_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
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
