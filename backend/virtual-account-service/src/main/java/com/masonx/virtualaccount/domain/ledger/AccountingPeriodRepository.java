package com.masonx.virtualaccount.domain.ledger;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.AccountingPeriodStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountingPeriodRepository {

    public static final String PLATFORM_MERCHANT_ID = "PLATFORM";

    private final JdbcTemplate jdbc;

    public AccountingPeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountingPeriod> findPlatformPeriod(Mode mode, String asset, LocalDate effectiveDate) {
        var rows = jdbc.query("""
                SELECT * FROM accounting_period
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                  AND asset = ?
                  AND ? BETWEEN period_start AND period_end
                ORDER BY period_start DESC
                LIMIT 1
                """, ROW_MAPPER, PLATFORM_MERCHANT_ID, mode.name(), asset, effectiveDate);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<AccountingPeriod> findById(String accountingPeriodId) {
        var rows = jdbc.query("""
                SELECT * FROM accounting_period
                WHERE accounting_period_id = ?
                  AND merchant_id = ?
                """, ROW_MAPPER, accountingPeriodId, PLATFORM_MERCHANT_ID);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean existsOverlappingPlatformPeriod(Mode mode, String asset,
                                                   LocalDate periodStart, LocalDate periodEnd) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM accounting_period
                    WHERE merchant_id = ?
                      AND mode = ?::va_mode
                      AND asset = ?
                      AND daterange(period_start, period_end, '[]')
                          && daterange(?::date, ?::date, '[]')
                )
                """, Boolean.class,
                PLATFORM_MERCHANT_ID, mode.name(), asset, periodStart, periodEnd);
        return Boolean.TRUE.equals(exists);
    }

    public List<AccountingPeriod> findPage(AccountingPeriodStatus status, int page, int size) {
        if (status == null) {
            return jdbc.query("""
                    SELECT * FROM accounting_period
                    WHERE merchant_id = ?
                    ORDER BY period_start DESC, asset ASC, mode ASC
                    LIMIT ? OFFSET ?
                    """, ROW_MAPPER, PLATFORM_MERCHANT_ID, size, (long) page * size);
        }
        return jdbc.query("""
                SELECT * FROM accounting_period
                WHERE merchant_id = ?
                  AND status = ?::accounting_period_status
                ORDER BY period_start DESC, asset ASC, mode ASC
                LIMIT ? OFFSET ?
                """, ROW_MAPPER, PLATFORM_MERCHANT_ID, status.name(), size, (long) page * size);
    }

    public long count(AccountingPeriodStatus status) {
        Long n;
        if (status == null) {
            n = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM accounting_period
                    WHERE merchant_id = ?
                    """, Long.class, PLATFORM_MERCHANT_ID);
        } else {
            n = jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM accounting_period
                    WHERE merchant_id = ?
                      AND status = ?::accounting_period_status
                    """, Long.class, PLATFORM_MERCHANT_ID, status.name());
        }
        return n != null ? n : 0L;
    }

    public void save(AccountingPeriod period) {
        jdbc.update("""
                INSERT INTO accounting_period (
                    accounting_period_id, merchant_id, mode, asset, period_start, period_end, status
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?, ?, ?::accounting_period_status
                )
                """,
                period.accountingPeriodId(),
                period.merchantId(),
                period.mode().name(),
                period.asset(),
                period.periodStart(),
                period.periodEnd(),
                period.status().name());
    }

    public void markClosed(String accountingPeriodId) {
        jdbc.update("""
                UPDATE accounting_period
                SET status = 'CLOSED'::accounting_period_status, closed_at = now(), updated_at = now()
                WHERE accounting_period_id = ?
                """, accountingPeriodId);
    }

    private static final RowMapper<AccountingPeriod> ROW_MAPPER = (rs, __) -> new AccountingPeriod(
            rs.getString("accounting_period_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("asset"),
            rs.getObject("period_start", LocalDate.class),
            rs.getObject("period_end", LocalDate.class),
            AccountingPeriodStatus.valueOf(rs.getString("status"))
    );
}
