package com.masonx.virtualaccount.domain;

import com.masonx.virtualaccount.domain.constant.VirtualCardStatus;
import com.masonx.virtualaccount.domain.po.VirtualCard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class VirtualCardRepository {

    private final JdbcTemplate jdbc;

    public VirtualCardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(VirtualCard card) {
        jdbc.update("""
                INSERT INTO virtual_card (
                    card_id, masked_pan, bin, vcc_account_id, hold_account_id, owner_account_id,
                    status, spending_limit, currency, expiry
                ) VALUES (?, ?, ?, ?, ?, ?, ?::va_virtual_card_status, ?, ?, ?)
                """,
                card.cardId(),
                card.maskedPan(),
                card.bin(),
                card.vccAccountId(),
                card.holdAccountId(),
                card.ownerAccountId(),
                card.status().name(),
                card.spendingLimit(),
                card.currency(),
                card.expiry() != null ? Date.valueOf(card.expiry()) : null);
    }

    public Optional<VirtualCard> findById(String cardId) {
        var rows = jdbc.query(
                "SELECT * FROM virtual_card WHERE card_id = ?",
                ROW_MAPPER, cardId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<VirtualCard> findByOwnerAccount(String ownerAccountId) {
        return jdbc.query(
                "SELECT * FROM virtual_card WHERE owner_account_id = ? ORDER BY created_at DESC",
                ROW_MAPPER, ownerAccountId);
    }

    public Optional<VirtualCard> findActiveByBin(String bin) {
        var rows = jdbc.query(
                "SELECT * FROM virtual_card WHERE bin = ? AND status = 'ACTIVE' LIMIT 1",
                ROW_MAPPER, bin);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Looks up an ACTIVE card by its masked PAN (first 6 + **** + last 4).
     * Used by the issuer auth endpoint to identify the card from the simulator request.
     */
    public Optional<VirtualCard> findActiveByMaskedPan(String maskedPan) {
        var rows = jdbc.query(
                "SELECT * FROM virtual_card WHERE masked_pan = ? AND status = 'ACTIVE' LIMIT 1",
                ROW_MAPPER, maskedPan);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Returns the total number of cards linked to the given merchant. */
    public long countByMerchantId(String merchantId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM virtual_card vc
                JOIN ledger_account a ON vc.owner_account_id = a.ledger_account_id
                WHERE a.merchant_id = ?
                """, Long.class, merchantId);
        return count != null ? count : 0L;
    }

    /** Lists cards linked to the given merchant, paginated by LIMIT/OFFSET. */
    public List<VirtualCard> findByMerchantId(String merchantId, int page, int size) {
        return jdbc.query("""
                SELECT vc.* FROM virtual_card vc
                JOIN ledger_account a ON vc.owner_account_id = a.ledger_account_id
                WHERE a.merchant_id = ?
                ORDER BY vc.created_at DESC
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, merchantId, size, (long) page * size);
    }

    public void updateStatus(String cardId, VirtualCardStatus status) {
        jdbc.update(
                "UPDATE virtual_card SET status = ?::va_virtual_card_status, updated_at = now() WHERE card_id = ?",
                status.name(), cardId);
    }

    private static final RowMapper<VirtualCard> ROW_MAPPER = (rs, __) -> {
        Date expiry = rs.getDate("expiry");
        return new VirtualCard(
                rs.getString("card_id"),
                rs.getString("masked_pan"),
                rs.getString("bin"),
                rs.getString("vcc_account_id"),
                rs.getString("hold_account_id"),
                rs.getString("owner_account_id"),
                VirtualCardStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("spending_limit"),
                rs.getString("currency"),
                expiry != null ? expiry.toLocalDate() : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    };
}
