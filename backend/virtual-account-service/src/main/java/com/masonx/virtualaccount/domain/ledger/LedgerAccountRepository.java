package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.po.LedgerAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class LedgerAccountRepository {

    private final JdbcTemplate jdbc;

    public LedgerAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Fetches the account row and acquires a row-level lock for the remainder
     * of the current transaction. Must be called within @Transactional.
     */
    public Optional<LedgerAccount> findByIdForUpdate(String ledgerAccountId) {
        var rows = jdbc.query(
                "SELECT * FROM ledger_account WHERE ledger_account_id = ? FOR UPDATE",
                ROW_MAPPER, ledgerAccountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<LedgerAccount> findById(String ledgerAccountId) {
        var rows = jdbc.query(
                "SELECT * FROM ledger_account WHERE ledger_account_id = ?",
                ROW_MAPPER, ledgerAccountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void save(LedgerAccount account) {
        jdbc.update(INSERT_SQL, insertArgs(account));
    }

    /**
     * Insert that tolerates losing a create race (e.g. the per-merchant
     * MERCHANT_RECEIVABLE arbiter index). Caller re-reads the surviving row.
     */
    public void saveIfAbsent(LedgerAccount account) {
        jdbc.update(INSERT_SQL + " ON CONFLICT DO NOTHING", insertArgs(account));
    }

    private static final String INSERT_SQL = """
            INSERT INTO ledger_account (
                ledger_account_id, mode, ledger_account_role, org_id, merchant_id, provider_id,
                ledger_account_type, asset, asset_class, scale, normal_balance, account_class,
                balance, status
            ) VALUES (
                ?, ?::va_mode, ?::ledger_account_role, ?, ?, ?,
                ?::ledger_account_type, ?, ?::va_asset_class, ?, ?::va_normal_balance, ?::va_account_class,
                ?, ?::ledger_account_status
            )""";

    private static Object[] insertArgs(LedgerAccount account) {
        return new Object[]{
                account.ledgerAccountId(),
                account.mode().name(),
                account.ledgerAccountRole().name(),
                account.orgId(),
                account.merchantId(),
                account.providerId(),
                account.ledgerAccountType().name(),
                account.asset(),
                account.assetClass().name(),
                account.scale(),
                account.normalBalance().name(),
                account.accountClass().name(),
                account.balance(),
                account.status().name()};
    }

    public Optional<LedgerAccount> findTenantAccount(String merchantId, Mode mode,
                                                  String asset, LedgerAccountType type) {
        var rows = jdbc.query("""
                SELECT * FROM ledger_account
                WHERE ledger_account_role = 'TENANT'
                  AND merchant_id = ?
                  AND mode = ?::va_mode
                  AND asset = ?
                  AND ledger_account_type = ?::ledger_account_type
                LIMIT 1
                """, ROW_MAPPER, merchantId, mode.name(), asset, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<LedgerAccount> findExternalAccount(String providerId, String asset, LedgerAccountType type) {
        var rows = jdbc.query("""
                SELECT * FROM ledger_account
                WHERE ledger_account_role = 'EXTERNAL'
                  AND provider_id = ?
                  AND asset = ?
                  AND ledger_account_type = ?::ledger_account_type
                LIMIT 1
                """, ROW_MAPPER, providerId, asset, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<LedgerAccount> findPlatformAccount(String asset, LedgerAccountType type) {
        var rows = jdbc.query("""
                SELECT * FROM ledger_account
                WHERE ledger_account_role = 'PLATFORM'
                  AND asset = ?
                  AND ledger_account_type = ?::ledger_account_type
                LIMIT 1
                """, ROW_MAPPER, asset, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Returns all TENANT accounts for a merchant, ordered by created_at desc, paginated. */
    public List<LedgerAccount> findTenantAccountsByMerchant(String merchantId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM ledger_account
                WHERE ledger_account_role = 'TENANT'
                  AND merchant_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, merchantId, size, (long) page * size);
    }

    public long countTenantAccountsByMerchant(String merchantId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ledger_account
                WHERE ledger_account_role = 'TENANT' AND merchant_id = ?
                """, Long.class, merchantId);
        return n != null ? n : 0L;
    }

    /**
     * Returns ALL accounts for a mode/asset combination — no LIMIT.
     * Intended for trial balance report only. When account count grows, add
     * pagination or a nightly-materialized snapshot (see docs/planning/ledger-completeness-plan.md).
     */
    public List<LedgerAccount> findAllByModeAndAsset(Mode mode, String asset) {
        return jdbc.query("""
                SELECT * FROM ledger_account
                WHERE mode = ?::va_mode AND asset = ?
                ORDER BY ledger_account_role, ledger_account_type, ledger_account_id
                """, ROW_MAPPER, mode.name(), asset);
    }

    /** Updates posted balance atomically — ledger engine only. */
    void updateLedgerBalance(String ledgerAccountId, BigDecimal balance) {
        jdbc.update(
                "UPDATE ledger_account SET balance = ?, updated_at = now() WHERE ledger_account_id = ?",
                balance, ledgerAccountId);
    }

    public void updateStatus(String ledgerAccountId, LedgerAccountStatus status) {
        jdbc.update(
                "UPDATE ledger_account SET status = ?::ledger_account_status, updated_at = now() WHERE ledger_account_id = ?",
                status.name(), ledgerAccountId);
    }

    private static final RowMapper<LedgerAccount> ROW_MAPPER = (rs, __) -> new LedgerAccount(
            rs.getString("ledger_account_id"),
            Mode.valueOf(rs.getString("mode")),
            LedgerAccountRole.valueOf(rs.getString("ledger_account_role")),
            rs.getString("org_id"),
            rs.getString("merchant_id"),
            rs.getString("provider_id"),
            LedgerAccountType.valueOf(rs.getString("ledger_account_type")),
            rs.getString("asset"),
            AssetClass.valueOf(rs.getString("asset_class")),
            rs.getInt("scale"),
            NormalBalance.valueOf(rs.getString("normal_balance")),
            AccountClass.valueOf(rs.getString("account_class")),
            rs.getBigDecimal("balance"),
            LedgerAccountStatus.valueOf(rs.getString("status"))
    );
}
