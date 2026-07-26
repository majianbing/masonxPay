package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardholderKycStatus;
import com.masonx.virtualaccount.domain.constant.CardholderType;
import com.masonx.virtualaccount.domain.po.Cardholder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CardholderRepository {

    private final JdbcTemplate jdbc;

    public CardholderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Cardholder cardholder) {
        jdbc.update("""
                INSERT INTO cardholder (
                    cardholder_id, merchant_id, mode, type, external_issuer_cardholder_id,
                    kyc_status, display_name, display_ref, profile_ref
                ) VALUES (
                    ?, ?, ?::va_mode, ?::cardholder_type, ?,
                    ?::cardholder_kyc_status, ?, ?, ?
                )
                """,
                cardholder.cardholderId(),
                cardholder.merchantId(),
                cardholder.mode().name(),
                cardholder.type().name(),
                cardholder.externalIssuerCardholderId(),
                cardholder.kycStatus().name(),
                cardholder.displayName(),
                cardholder.displayRef(),
                cardholder.profileRef());
    }

    public Optional<Cardholder> findById(String cardholderId) {
        var rows = jdbc.query(
                "SELECT * FROM cardholder WHERE cardholder_id = ?",
                ROW_MAPPER, cardholderId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Cardholder> findByIdForMerchant(String cardholderId, String merchantId, Mode mode) {
        var rows = jdbc.query("""
                SELECT * FROM cardholder
                WHERE cardholder_id = ?
                  AND merchant_id = ?
                  AND mode = ?::va_mode
                """, ROW_MAPPER, cardholderId, merchantId, mode.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Cardholder> findByMerchant(String merchantId, Mode mode, int page, int size) {
        return jdbc.query("""
                SELECT * FROM cardholder
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, merchantId, mode.name(), size, (long) page * size);
    }

    public long countByMerchant(String merchantId, Mode mode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM cardholder
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                """, Long.class, merchantId, mode.name());
        return count != null ? count : 0L;
    }

    public void updateKycStatus(String cardholderId, CardholderKycStatus status) {
        jdbc.update("""
                UPDATE cardholder
                SET kyc_status = ?::cardholder_kyc_status, updated_at = now()
                WHERE cardholder_id = ?
                """, status.name(), cardholderId);
    }

    private static final RowMapper<Cardholder> ROW_MAPPER = (rs, __) -> new Cardholder(
            rs.getString("cardholder_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            CardholderType.valueOf(rs.getString("type")),
            rs.getString("external_issuer_cardholder_id"),
            CardholderKycStatus.valueOf(rs.getString("kyc_status")),
            rs.getString("display_name"),
            rs.getString("display_ref"),
            rs.getString("profile_ref"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
}
