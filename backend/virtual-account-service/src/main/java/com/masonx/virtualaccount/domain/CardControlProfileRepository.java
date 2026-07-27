package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.po.CardControlProfile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class CardControlProfileRepository {

    private final JdbcTemplate jdbc;

    public CardControlProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(CardControlProfile profile) {
        jdbc.update("""
                INSERT INTO card_control_profile (
                    card_id, merchant_id, mode, controls_json
                ) VALUES (
                    ?, ?, ?, ?::jsonb
                )
                ON CONFLICT (card_id) DO UPDATE SET
                    merchant_id = EXCLUDED.merchant_id,
                    mode = EXCLUDED.mode,
                    controls_json = EXCLUDED.controls_json,
                    updated_at = now()
                """,
                profile.cardId(),
                profile.merchantId(),
                profile.mode().name(),
                profile.controlsJson() != null ? profile.controlsJson() : "{}");
    }

    public Optional<CardControlProfile> findByCardIdForMerchant(String cardId, String merchantId, Mode mode) {
        var rows = jdbc.query("""
                SELECT * FROM card_control_profile
                WHERE card_id = ? AND merchant_id = ? AND mode = ?
                """, ROW_MAPPER, cardId, merchantId, mode.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static final RowMapper<CardControlProfile> ROW_MAPPER = (rs, __) -> new CardControlProfile(
            rs.getString("card_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("controls_json"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
}
