package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.PrepaidFeeScheduleStatus;
import com.masonx.virtualaccount.domain.po.PrepaidFeeSchedule;
import com.masonx.virtualaccount.domain.po.PrepaidFeeScheduleVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class PrepaidFeeScheduleRepository {

    private final JdbcTemplate jdbc;

    public PrepaidFeeScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(PrepaidFeeSchedule schedule) {
        jdbc.update("""
                INSERT INTO prepaid_fee_schedule (
                    schedule_id, merchant_id, mode, program_id, bin, channel, name, status
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?, ?, ?, ?::prepaid_fee_schedule_status
                )
                """,
                schedule.scheduleId(),
                schedule.merchantId(),
                schedule.mode().name(),
                schedule.programId(),
                schedule.bin(),
                schedule.channel(),
                schedule.name(),
                schedule.status().name());
    }

    public Optional<PrepaidFeeSchedule> findByIdForMerchant(String scheduleId, String merchantId, Mode mode) {
        List<PrepaidFeeSchedule> rows = jdbc.query("""
                SELECT * FROM prepaid_fee_schedule
                WHERE schedule_id = ?
                  AND merchant_id = ?
                  AND mode = ?::va_mode
                """, SCHEDULE_MAPPER, scheduleId, merchantId, mode.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<PrepaidFeeSchedule> listForMerchant(String merchantId, Mode mode, int page, int size) {
        return jdbc.query("""
                SELECT * FROM prepaid_fee_schedule
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, SCHEDULE_MAPPER, merchantId, mode.name(), size, (long) page * size);
    }

    public long countForMerchant(String merchantId, Mode mode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM prepaid_fee_schedule
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                """, Long.class, merchantId, mode.name());
        return count != null ? count : 0L;
    }

    public void saveVersion(PrepaidFeeScheduleVersion version) {
        jdbc.update("""
                INSERT INTO prepaid_fee_schedule_version (
                    schedule_id, merchant_id, mode, version, status, fee_currency, fee_scale,
                    rounding_mode, effective_from, effective_to, rules_json, metadata_json, published_at
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?::prepaid_fee_schedule_status, ?, ?,
                    ?, ?, ?, ?::jsonb, ?::jsonb, ?
                )
                """,
                version.scheduleId(),
                version.merchantId(),
                version.mode().name(),
                version.version(),
                version.status().name(),
                version.feeCurrency(),
                version.feeScale(),
                version.roundingMode(),
                Timestamp.from(version.effectiveFrom()),
                version.effectiveTo() != null ? Timestamp.from(version.effectiveTo()) : null,
                jsonOrDefault(version.rulesJson(), "[]"),
                jsonOrDefault(version.metadataJson(), "{}"),
                version.publishedAt() != null ? Timestamp.from(version.publishedAt()) : null);
    }

    public Optional<PrepaidFeeScheduleVersion> findActiveVersion(String merchantId,
                                                                 Mode mode,
                                                                 String programId,
                                                                 String bin,
                                                                 String channel,
                                                                 Instant asOf) {
        List<PrepaidFeeScheduleVersion> rows = jdbc.query("""
                SELECT v.*
                FROM prepaid_fee_schedule_version v
                JOIN prepaid_fee_schedule s
                  ON s.schedule_id = v.schedule_id
                 AND s.merchant_id = v.merchant_id
                 AND s.mode = v.mode
                WHERE s.merchant_id = ?
                  AND s.mode = ?::va_mode
                  AND s.status = 'ACTIVE'
                  AND v.status = 'ACTIVE'
                  AND v.effective_from <= ?
                  AND (v.effective_to IS NULL OR v.effective_to > ?)
                  AND (s.program_id = ? OR s.program_id IS NULL)
                  AND (s.bin = ? OR s.bin IS NULL)
                  AND (s.channel = ? OR s.channel IS NULL)
                ORDER BY
                  CASE WHEN s.program_id = ? THEN 0 ELSE 1 END,
                  CASE WHEN s.bin = ? THEN 0 ELSE 1 END,
                  CASE WHEN s.channel = ? THEN 0 ELSE 1 END,
                  v.effective_from DESC,
                  v.version DESC
                LIMIT 1
                """,
                VERSION_MAPPER,
                merchantId,
                mode.name(),
                Timestamp.from(asOf),
                Timestamp.from(asOf),
                programId,
                bin,
                channel,
                programId,
                bin,
                channel);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<PrepaidFeeScheduleVersion> listVersions(String scheduleId, String merchantId, Mode mode) {
        return jdbc.query("""
                SELECT v.*
                FROM prepaid_fee_schedule_version v
                WHERE v.schedule_id = ?
                  AND v.merchant_id = ?
                  AND v.mode = ?::va_mode
                ORDER BY v.version DESC
                """, VERSION_MAPPER, scheduleId, merchantId, mode.name());
    }

    private static String jsonOrDefault(String json, String fallback) {
        return json != null ? json : fallback;
    }

    private static final RowMapper<PrepaidFeeSchedule> SCHEDULE_MAPPER = (rs, __) -> new PrepaidFeeSchedule(
            rs.getString("schedule_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("program_id"),
            rs.getString("bin"),
            rs.getString("channel"),
            rs.getString("name"),
            PrepaidFeeScheduleStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private static final RowMapper<PrepaidFeeScheduleVersion> VERSION_MAPPER = (rs, __) -> new PrepaidFeeScheduleVersion(
            rs.getString("schedule_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getInt("version"),
            PrepaidFeeScheduleStatus.valueOf(rs.getString("status")),
            rs.getString("fee_currency"),
            rs.getInt("fee_scale"),
            rs.getString("rounding_mode"),
            rs.getTimestamp("effective_from").toInstant(),
            rs.getTimestamp("effective_to") != null ? rs.getTimestamp("effective_to").toInstant() : null,
            rs.getString("rules_json"),
            rs.getString("metadata_json"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("published_at") != null ? rs.getTimestamp("published_at").toInstant() : null);
}
