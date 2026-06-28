package com.masonx.paygateway.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes payment_read_models table and index sizes as Micrometer gauges.
 * Refreshed every minute so Prometheus can scrape size trends without
 * custom postgres-exporter queries.
 */
@Component
public class ReadModelMetrics {

    private static final Logger log = LoggerFactory.getLogger(ReadModelMetrics.class);

    private final JdbcTemplate jdbc;

    private final AtomicLong tableBytes = new AtomicLong(0);
    private final AtomicLong indexBytes = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);

    public ReadModelMetrics(@Qualifier("flywayDataSource") DataSource dataSource,
                            MeterRegistry registry) {
        this.jdbc = new JdbcTemplate(dataSource);

        Gauge.builder("payment_read_model_table_bytes", tableBytes, AtomicLong::get)
                .description("payment_read_models heap size in bytes (excluding indexes)")
                .register(registry);

        Gauge.builder("payment_read_model_index_bytes", indexBytes, AtomicLong::get)
                .description("payment_read_models total index size in bytes")
                .register(registry);

        Gauge.builder("payment_read_model_total_bytes", totalBytes, AtomicLong::get)
                .description("payment_read_models total size (table + indexes + TOAST) in bytes")
                .register(registry);
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    @Scheduled(fixedDelay = 60_000)
    public void refresh() {
        try {
            jdbc.query("""
                    SELECT
                        pg_table_size('payment_read_models')   AS table_bytes,
                        pg_indexes_size('payment_read_models') AS index_bytes,
                        pg_total_relation_size('payment_read_models') AS total_bytes
                    """,
                    rs -> {
                        tableBytes.set(rs.getLong("table_bytes"));
                        indexBytes.set(rs.getLong("index_bytes"));
                        totalBytes.set(rs.getLong("total_bytes"));
                    });
        } catch (Exception e) {
            log.warn("Failed to refresh read model size metrics: {}", e.getMessage());
        }
    }
}
