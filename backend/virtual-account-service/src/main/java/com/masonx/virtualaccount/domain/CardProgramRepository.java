package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardProgramFundingModel;
import com.masonx.virtualaccount.domain.constant.CardProgramStatus;
import com.masonx.virtualaccount.domain.constant.CardProgramSystemOfRecord;
import com.masonx.virtualaccount.domain.po.CardProgram;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CardProgramRepository {

    private final JdbcTemplate jdbc;

    public CardProgramRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(CardProgram program) {
        jdbc.update("""
                INSERT INTO card_program (
                    program_id, merchant_id, mode, issuer_partner_id, name, currency,
                    bin_range_metadata, status, system_of_record, funding_model,
                    feature_flags_json, default_controls_json, settlement_model_json,
                    fee_schedule_id, external_program_id, external_funding_source_id
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?, ?,
                    ?::jsonb, ?::card_program_status, ?::card_program_system_of_record,
                    ?::card_program_funding_model,
                    ?::jsonb, ?::jsonb, ?::jsonb,
                    ?, ?, ?
                )
                """,
                program.programId(),
                program.merchantId(),
                program.mode().name(),
                program.issuerPartnerId(),
                program.name(),
                program.currency(),
                jsonOrEmpty(program.binRangeMetadata()),
                program.status().name(),
                program.systemOfRecord().name(),
                program.fundingModel().name(),
                jsonOrEmpty(program.featureFlagsJson()),
                jsonOrEmpty(program.defaultControlsJson()),
                jsonOrEmpty(program.settlementModelJson()),
                program.feeScheduleId(),
                program.externalProgramId(),
                program.externalFundingSourceId());
    }

    public Optional<CardProgram> findById(String programId) {
        var rows = jdbc.query(
                "SELECT * FROM card_program WHERE program_id = ?",
                ROW_MAPPER, programId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CardProgram> findByIdForMerchant(String programId, String merchantId, Mode mode) {
        var rows = jdbc.query("""
                SELECT * FROM card_program
                WHERE program_id = ?
                  AND merchant_id = ?
                  AND mode = ?::va_mode
                """, ROW_MAPPER, programId, merchantId, mode.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<CardProgram> findByMerchant(String merchantId, Mode mode, int page, int size) {
        return jdbc.query("""
                SELECT * FROM card_program
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, merchantId, mode.name(), size, (long) page * size);
    }

    public long countByMerchant(String merchantId, Mode mode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM card_program
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                """, Long.class, merchantId, mode.name());
        return count != null ? count : 0L;
    }

    public void updateStatus(String programId, CardProgramStatus status) {
        jdbc.update("""
                UPDATE card_program
                SET status = ?::card_program_status, updated_at = now()
                WHERE program_id = ?
                """, status.name(), programId);
    }

    private static String jsonOrEmpty(String json) {
        return json != null ? json : "{}";
    }

    private static final RowMapper<CardProgram> ROW_MAPPER = (rs, __) -> new CardProgram(
            rs.getString("program_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("issuer_partner_id"),
            rs.getString("name"),
            rs.getString("currency"),
            rs.getString("bin_range_metadata"),
            CardProgramStatus.valueOf(rs.getString("status")),
            CardProgramSystemOfRecord.valueOf(rs.getString("system_of_record")),
            CardProgramFundingModel.valueOf(rs.getString("funding_model")),
            rs.getString("feature_flags_json"),
            rs.getString("default_controls_json"),
            rs.getString("settlement_model_json"),
            rs.getString("fee_schedule_id"),
            rs.getString("external_program_id"),
            rs.getString("external_funding_source_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
}
