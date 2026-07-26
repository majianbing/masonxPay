package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerStatus;
import com.masonx.virtualaccount.domain.constant.IssuerPartnerType;
import com.masonx.virtualaccount.domain.po.IssuerPartner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class IssuerPartnerRepository {

    private final JdbcTemplate jdbc;

    public IssuerPartnerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(IssuerPartner partner) {
        jdbc.update("""
                INSERT INTO issuer_partner (
                    issuer_partner_id, merchant_id, mode, name, adapter_type, status,
                    credentials_ref, config_json, webhook_secret_ref,
                    external_program_id, external_funding_source_id
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?::issuer_partner_type, ?::issuer_partner_status,
                    ?, ?::jsonb, ?,
                    ?, ?
                )
                """,
                partner.issuerPartnerId(),
                partner.merchantId(),
                partner.mode().name(),
                partner.name(),
                partner.adapterType().name(),
                partner.status().name(),
                partner.credentialsRef(),
                jsonOrEmpty(partner.configJson()),
                partner.webhookSecretRef(),
                partner.externalProgramId(),
                partner.externalFundingSourceId());
    }

    public Optional<IssuerPartner> findById(String issuerPartnerId) {
        var rows = jdbc.query(
                "SELECT * FROM issuer_partner WHERE issuer_partner_id = ?",
                ROW_MAPPER, issuerPartnerId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<IssuerPartner> findByIdForMerchant(String issuerPartnerId, String merchantId, Mode mode) {
        var rows = jdbc.query("""
                SELECT * FROM issuer_partner
                WHERE issuer_partner_id = ?
                  AND merchant_id = ?
                  AND mode = ?::va_mode
                """, ROW_MAPPER, issuerPartnerId, merchantId, mode.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<IssuerPartner> findByMerchant(String merchantId, Mode mode, int page, int size) {
        return jdbc.query("""
                SELECT * FROM issuer_partner
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, merchantId, mode.name(), size, (long) page * size);
    }

    public long countByMerchant(String merchantId, Mode mode) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM issuer_partner
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                """, Long.class, merchantId, mode.name());
        return count != null ? count : 0L;
    }

    public void updateStatus(String issuerPartnerId, IssuerPartnerStatus status) {
        jdbc.update("""
                UPDATE issuer_partner
                SET status = ?::issuer_partner_status, updated_at = now()
                WHERE issuer_partner_id = ?
                """, status.name(), issuerPartnerId);
    }

    private static String jsonOrEmpty(String json) {
        return json != null ? json : "{}";
    }

    private static final RowMapper<IssuerPartner> ROW_MAPPER = (rs, __) -> new IssuerPartner(
            rs.getString("issuer_partner_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("name"),
            IssuerPartnerType.valueOf(rs.getString("adapter_type")),
            IssuerPartnerStatus.valueOf(rs.getString("status")),
            rs.getString("credentials_ref"),
            rs.getString("config_json"),
            rs.getString("webhook_secret_ref"),
            rs.getString("external_program_id"),
            rs.getString("external_funding_source_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());
}
