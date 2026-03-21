package com.masonx.paygateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Manages 6-month range partitions for the gateway_logs table.
 *
 * Runs on the 1st of each month:
 *   1. Ensures the next 6-month partition exists (pre-creates it ~3-5 months early).
 *   2. Drops partitions older than the configured retention window.
 *
 * Partition naming: gateway_logs_YYYY_h1 (Jan–Jun) / gateway_logs_YYYY_h2 (Jul–Dec).
 * Default retention: 4 periods = 2 years. Override with app.logs.retention-periods.
 */
@Service
public class GatewayLogPartitionService {

    private static final Logger log = LoggerFactory.getLogger(GatewayLogPartitionService.class);

    @Value("${app.logs.retention-periods:4}")
    private int retentionPeriods;

    private final JdbcTemplate jdbc;

    public GatewayLogPartitionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 0 1 1 * *") // 01:00 on the 1st of every month
    @Transactional
    public void managePartitions() {
        LocalDate current = periodStart(LocalDate.now());
        ensurePartitionExists(current);
        ensurePartitionExists(current.plusMonths(6));   // pre-create next period
        dropExpiredPartitions(current);
    }

    /** Creates the partition for the given period start date if it doesn't exist. */
    public void ensurePartitionExists(LocalDate periodStart) {
        String name = partitionName(periodStart);
        LocalDate periodEnd = periodStart.plusMonths(6);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s PARTITION OF gateway_logs
                FOR VALUES FROM ('%s 00:00:00+00') TO ('%s 00:00:00+00')
                """.formatted(name, periodStart, periodEnd));
        log.info("Ensured partition: {} ({} – {})", name, periodStart, periodEnd);
    }

    private void dropExpiredPartitions(LocalDate currentPeriod) {
        LocalDate cutoff = currentPeriod.minusMonths((long) retentionPeriods * 6);

        List<String> candidates = jdbc.queryForList(
                "SELECT relname FROM pg_class WHERE relname LIKE 'gateway_logs_%' AND relkind = 'r'",
                String.class);

        for (String partition : candidates) {
            LocalDate start = parsePeriodStart(partition);
            if (start != null && start.isBefore(cutoff)) {
                jdbc.execute("DROP TABLE IF EXISTS " + partition);
                log.info("Dropped expired partition: {} (period started {})", partition, start);
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Returns the start of the 6-month period containing the given date. */
    static LocalDate periodStart(LocalDate date) {
        return date.getMonthValue() <= 6
                ? LocalDate.of(date.getYear(), 1, 1)
                : LocalDate.of(date.getYear(), 7, 1);
    }

    /** gateway_logs_2026_h1 or gateway_logs_2026_h2 */
    static String partitionName(LocalDate periodStart) {
        String half = periodStart.getMonthValue() == 1 ? "h1" : "h2";
        return "gateway_logs_" + periodStart.getYear() + "_" + half;
    }

    /**
     * Parses the period-start date from a partition name.
     * Returns null if the name doesn't match the expected pattern.
     */
    static LocalDate parsePeriodStart(String name) {
        // Expected format: gateway_logs_YYYY_hN
        String[] parts = name.split("_");
        if (parts.length != 4) return null;
        try {
            int year = Integer.parseInt(parts[2]);
            int month = switch (parts[3]) {
                case "h1" -> 1;
                case "h2" -> 7;
                default   -> -1;
            };
            if (month < 0) return null;
            return LocalDate.of(year, month, 1);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
