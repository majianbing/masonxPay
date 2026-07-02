package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.*;
import com.masonx.virtualaccount.domain.po.VaAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepository {

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Fetches the account row and acquires a row-level lock for the remainder
     * of the current transaction. Must be called within @Transactional.
     */
    public Optional<VaAccount> findByIdForUpdate(String accountId) {
        var rows = jdbc.query(
                "SELECT * FROM va_account WHERE account_id = ? FOR UPDATE",
                ROW_MAPPER, accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<VaAccount> findById(String accountId) {
        var rows = jdbc.query(
                "SELECT * FROM va_account WHERE account_id = ?",
                ROW_MAPPER, accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void save(VaAccount account) {
        jdbc.update("""
                INSERT INTO va_account (
                    account_id, mode, account_role, org_id, merchant_id, provider_id,
                    account_type, asset, asset_class, scale, normal_balance,
                    balance, frozen_balance, status
                ) VALUES (
                    ?, ?::va_mode, ?::va_account_role, ?, ?, ?,
                    ?::va_account_type, ?, ?::va_asset_class, ?, ?::va_normal_balance,
                    ?, ?, ?::va_account_status
                )
                """,
                account.accountId(),
                account.mode().name(),
                account.accountRole().name(),
                account.orgId(),
                account.merchantId(),
                account.providerId(),
                account.accountType().name(),
                account.asset(),
                account.assetClass().name(),
                account.scale(),
                account.normalBalance().name(),
                account.balance(),
                account.frozenBalance(),
                account.status().name());
    }

    public Optional<VaAccount> findTenantAccount(String merchantId, Mode mode,
                                                  String asset, AccountType type) {
        var rows = jdbc.query("""
                SELECT * FROM va_account
                WHERE account_role = 'TENANT'
                  AND merchant_id = ?
                  AND mode = ?::va_mode
                  AND asset = ?
                  AND account_type = ?::va_account_type
                LIMIT 1
                """, ROW_MAPPER, merchantId, mode.name(), asset, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<VaAccount> findExternalAccount(String providerId, String asset, AccountType type) {
        var rows = jdbc.query("""
                SELECT * FROM va_account
                WHERE account_role = 'EXTERNAL'
                  AND provider_id = ?
                  AND asset = ?
                  AND account_type = ?::va_account_type
                LIMIT 1
                """, ROW_MAPPER, providerId, asset, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<VaAccount> findPlatformAccount(String asset, AccountType type) {
        var rows = jdbc.query("""
                SELECT * FROM va_account
                WHERE account_role = 'PLATFORM'
                  AND asset = ?
                  AND account_type = ?::va_account_type
                LIMIT 1
                """, ROW_MAPPER, asset, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Returns all TENANT accounts for a merchant, ordered by created_at desc, paginated. */
    public List<VaAccount> findTenantAccountsByMerchant(String merchantId, int page, int size) {
        return jdbc.query("""
                SELECT * FROM va_account
                WHERE account_role = 'TENANT'
                  AND merchant_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, merchantId, size, (long) page * size);
    }

    public long countTenantAccountsByMerchant(String merchantId) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM va_account
                WHERE account_role = 'TENANT' AND merchant_id = ?
                """, Long.class, merchantId);
        return n != null ? n : 0L;
    }

    /**
     * Returns ALL accounts for a mode/asset combination — no LIMIT.
     * Intended for trial balance report only. When account count grows, add
     * pagination or a nightly-materialized snapshot (see docs/planning/ledger-completeness-plan.md).
     */
    public List<VaAccount> findAllByModeAndAsset(Mode mode, String asset) {
        return jdbc.query("""
                SELECT * FROM va_account
                WHERE mode = ?::va_mode AND asset = ?
                ORDER BY account_role, account_type, account_id
                """, ROW_MAPPER, mode.name(), asset);
    }

    /** Updates posted balance and frozen balance atomically — ledger engine only. */
    void updateLedgerBalance(String accountId, BigDecimal balance, BigDecimal frozenBalance) {
        jdbc.update(
                "UPDATE va_account SET balance = ?, frozen_balance = ?, updated_at = now() WHERE account_id = ?",
                balance, frozenBalance, accountId);
    }

    /** Updates only frozen_balance, leaving the ledger-owned posted balance untouched. */
    public void updateFrozenBalance(String accountId, BigDecimal frozenBalance) {
        jdbc.update(
                "UPDATE va_account SET frozen_balance = ?, updated_at = now() WHERE account_id = ?",
                frozenBalance, accountId);
    }

    public void updateStatus(String accountId, AccountStatus status) {
        jdbc.update(
                "UPDATE va_account SET status = ?::va_account_status, updated_at = now() WHERE account_id = ?",
                status.name(), accountId);
    }

    private static final RowMapper<VaAccount> ROW_MAPPER = (rs, __) -> new VaAccount(
            rs.getString("account_id"),
            Mode.valueOf(rs.getString("mode")),
            AccountRole.valueOf(rs.getString("account_role")),
            rs.getString("org_id"),
            rs.getString("merchant_id"),
            rs.getString("provider_id"),
            AccountType.valueOf(rs.getString("account_type")),
            rs.getString("asset"),
            AssetClass.valueOf(rs.getString("asset_class")),
            rs.getInt("scale"),
            NormalBalance.valueOf(rs.getString("normal_balance")),
            rs.getBigDecimal("balance"),
            rs.getBigDecimal("frozen_balance"),
            AccountStatus.valueOf(rs.getString("status"))
    );
}
