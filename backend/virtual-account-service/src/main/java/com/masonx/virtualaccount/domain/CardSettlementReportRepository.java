package com.masonx.virtualaccount.domain;

import com.masonx.common.tenant.Mode;
import com.masonx.virtualaccount.domain.constant.CardSettlementReportLineStatus;
import com.masonx.virtualaccount.domain.constant.CardSettlementReportStatus;
import com.masonx.virtualaccount.domain.po.CardSettlementReport;
import com.masonx.virtualaccount.domain.po.CardSettlementReportLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class CardSettlementReportRepository {

    private final JdbcTemplate jdbc;

    public CardSettlementReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertReport(CardSettlementReport report) {
        jdbc.update("""
                INSERT INTO card_settlement_report (
                    report_id, merchant_id, mode, program_id, issuer_partner_id, report_ref,
                    settlement_date, currency, total_amount, matched_amount, exception_amount,
                    line_count, exception_count, status
                ) VALUES (
                    ?, ?, ?::va_mode, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::card_settlement_report_status
                )
                """,
                report.reportId(),
                report.merchantId(),
                report.mode().name(),
                report.programId(),
                report.issuerPartnerId(),
                report.reportRef(),
                Date.valueOf(report.settlementDate()),
                report.currency(),
                report.totalAmount(),
                report.matchedAmount(),
                report.exceptionAmount(),
                report.lineCount(),
                report.exceptionCount(),
                report.status().name());
    }

    public void insertLine(CardSettlementReportLine line) {
        jdbc.update("""
                INSERT INTO card_settlement_report_line (
                    report_line_id, report_id, merchant_id, mode, program_id, rail_payment_id,
                    issuer_transaction_id, movement_type, amount, currency, matched_clearing_event_id,
                    matched_card_id, status, mismatch_amount, detail
                ) VALUES (
                    ?, ?, ?, ?::va_mode, ?, ?, ?, ?, ?, ?, ?, ?, ?::card_settlement_report_line_status, ?, ?
                )
                """,
                line.reportLineId(),
                line.reportId(),
                line.merchantId(),
                line.mode().name(),
                line.programId(),
                line.railPaymentId(),
                line.issuerTransactionId(),
                line.movementType(),
                line.amount(),
                line.currency(),
                line.matchedClearingEventId(),
                line.matchedCardId(),
                line.status().name(),
                line.mismatchAmount(),
                line.detail());
    }

    public Optional<CardSettlementReport> findReport(String reportId, String merchantId, Mode mode) {
        var rows = jdbc.query("""
                SELECT * FROM card_settlement_report
                WHERE report_id = ?
                  AND merchant_id = ?
                  AND mode = ?::va_mode
                """, REPORT_MAPPER, reportId, merchantId, mode.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CardSettlementReport> findByReportRef(String merchantId, Mode mode,
                                                          String issuerPartnerId, String reportRef) {
        var rows = jdbc.query("""
                SELECT * FROM card_settlement_report
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                  AND issuer_partner_id = ?
                  AND report_ref = ?
                """, REPORT_MAPPER, merchantId, mode.name(), issuerPartnerId, reportRef);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<CardSettlementReportLine> findLines(String reportId, String merchantId, Mode mode) {
        return jdbc.query("""
                SELECT * FROM card_settlement_report_line
                WHERE report_id = ?
                  AND merchant_id = ?
                  AND mode = ?::va_mode
                ORDER BY created_at ASC, report_line_id ASC
                """, LINE_MAPPER, reportId, merchantId, mode.name());
    }

    public List<CardSettlementReport> findByProgram(String merchantId, Mode mode, String programId,
                                                    int page, int size) {
        return jdbc.query("""
                SELECT * FROM card_settlement_report
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                  AND program_id = ?
                ORDER BY settlement_date DESC, created_at DESC
                LIMIT ? OFFSET ?
                """, REPORT_MAPPER, merchantId, mode.name(), programId, size, (long) page * size);
    }

    public long countByProgram(String merchantId, Mode mode, String programId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM card_settlement_report
                WHERE merchant_id = ?
                  AND mode = ?::va_mode
                  AND program_id = ?
                """, Long.class, merchantId, mode.name(), programId);
        return count != null ? count : 0L;
    }

    public CardSettlementSummaryAmounts summarizeProgramDate(String merchantId, Mode mode, String programId,
                                                             LocalDate settlementDate, String currency) {
        return jdbc.queryForObject("""
                WITH report_totals AS (
                    SELECT
                        COALESCE(SUM(total_amount), 0) AS issuer_report_amount,
                        COALESCE(SUM(matched_amount), 0) AS matched_report_amount,
                        COALESCE(SUM(exception_amount), 0) AS exception_amount,
                        COALESCE(SUM(line_count), 0) AS line_count,
                        COALESCE(SUM(exception_count), 0) AS exception_count
                    FROM card_settlement_report
                    WHERE merchant_id = ?
                      AND mode = ?::va_mode
                      AND program_id = ?
                      AND settlement_date = ?
                      AND currency = ?
                ),
                clearing_totals AS (
                    SELECT COALESCE(SUM(ce.amount), 0) AS clearing_amount
                    FROM card_settlement_report_line line
                    JOIN card_clearing_event ce
                      ON ce.clearing_event_id = line.matched_clearing_event_id
                    WHERE line.merchant_id = ?
                      AND line.mode = ?::va_mode
                      AND line.program_id = ?
                      AND line.status = 'MATCHED'
                      AND line.currency = ?
                      AND line.report_id IN (
                          SELECT report_id
                          FROM card_settlement_report
                          WHERE merchant_id = ?
                            AND mode = ?::va_mode
                            AND program_id = ?
                            AND settlement_date = ?
                            AND currency = ?
                      )
                ),
                ledger_totals AS (
                    SELECT COALESCE(SUM(line.amount), 0) AS ledger_posted_amount
                    FROM card_settlement_report_line line
                    WHERE line.merchant_id = ?
                      AND line.mode = ?::va_mode
                      AND line.program_id = ?
                      AND line.status = 'MATCHED'
                      AND line.currency = ?
                      AND line.report_id IN (
                          SELECT report_id
                          FROM card_settlement_report
                          WHERE merchant_id = ?
                            AND mode = ?::va_mode
                            AND program_id = ?
                            AND settlement_date = ?
                            AND currency = ?
                      )
                      AND EXISTS (
                          SELECT 1
                          FROM va_transaction tx
                          WHERE tx.payment_reference_id = line.rail_payment_id
                            AND tx.mode = line.mode
                            AND tx.status = 'POSTED'
                      )
                )
                SELECT
                    rt.issuer_report_amount,
                    rt.matched_report_amount,
                    rt.exception_amount,
                    rt.line_count,
                    rt.exception_count,
                    ct.clearing_amount,
                    lt.ledger_posted_amount
                FROM report_totals rt
                CROSS JOIN clearing_totals ct
                CROSS JOIN ledger_totals lt
                """, (rs, __) -> new CardSettlementSummaryAmounts(
                        rs.getBigDecimal("issuer_report_amount"),
                        rs.getBigDecimal("matched_report_amount"),
                        rs.getBigDecimal("exception_amount"),
                        rs.getInt("line_count"),
                        rs.getInt("exception_count"),
                        rs.getBigDecimal("clearing_amount"),
                        rs.getBigDecimal("ledger_posted_amount")),
                merchantId, mode.name(), programId, settlementDate, currency,
                merchantId, mode.name(), programId, currency,
                merchantId, mode.name(), programId, settlementDate, currency,
                merchantId, mode.name(), programId, currency,
                merchantId, mode.name(), programId, settlementDate, currency);
    }

    public record CardSettlementSummaryAmounts(
            BigDecimal issuerReportAmount,
            BigDecimal matchedReportAmount,
            BigDecimal exceptionAmount,
            int lineCount,
            int exceptionCount,
            BigDecimal clearingAmount,
            BigDecimal ledgerPostedAmount
    ) {
    }

    private static final RowMapper<CardSettlementReport> REPORT_MAPPER = (rs, __) -> new CardSettlementReport(
            rs.getString("report_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("program_id"),
            rs.getString("issuer_partner_id"),
            rs.getString("report_ref"),
            rs.getDate("settlement_date").toLocalDate(),
            rs.getString("currency"),
            rs.getBigDecimal("total_amount"),
            rs.getBigDecimal("matched_amount"),
            rs.getBigDecimal("exception_amount"),
            rs.getInt("line_count"),
            rs.getInt("exception_count"),
            CardSettlementReportStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<CardSettlementReportLine> LINE_MAPPER = (rs, __) -> new CardSettlementReportLine(
            rs.getString("report_line_id"),
            rs.getString("report_id"),
            rs.getString("merchant_id"),
            Mode.valueOf(rs.getString("mode")),
            rs.getString("program_id"),
            rs.getString("rail_payment_id"),
            rs.getString("issuer_transaction_id"),
            rs.getString("movement_type"),
            rs.getBigDecimal("amount"),
            rs.getString("currency"),
            rs.getString("matched_clearing_event_id"),
            rs.getString("matched_card_id"),
            CardSettlementReportLineStatus.valueOf(rs.getString("status")),
            rs.getBigDecimal("mismatch_amount"),
            rs.getString("detail"),
            rs.getTimestamp("created_at").toInstant());
}
