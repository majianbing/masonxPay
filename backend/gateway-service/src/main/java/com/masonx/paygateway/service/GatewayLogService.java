package com.masonx.paygateway.service;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.log.GatewayLog;
import com.masonx.paygateway.domain.log.GatewayLogType;
import com.masonx.paygateway.web.dto.GatewayLogResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class GatewayLogService {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public GatewayLogService(@Qualifier("flywayDataSource") DataSource dataSource) {
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Persists a log entry asynchronously so it never blocks the request thread.
     * Uses the physical datasource because gateway_logs is a PostgreSQL partitioned table,
     * while ShardingSphere only routes the payment core logical tables.
     */
    @Async("webhookExecutor")
    public void log(GatewayLog entry) {
        jdbcTemplate.update("""
                INSERT INTO gateway_logs (
                    merchant_id, api_key_id, request_id, type, method, path,
                    request_headers, request_body, response_status, response_body,
                    duration_ms, mode, trace_id, created_at
                ) VALUES (
                    :merchantId, :apiKeyId, :requestId, :type, :method, :path,
                    :requestHeaders, :requestBody, :responseStatus, :responseBody,
                    :durationMs, :mode, :traceId, :createdAt
                )
                """, new MapSqlParameterSource()
                .addValue("merchantId", entry.getMerchantId())
                .addValue("apiKeyId", entry.getApiKeyId())
                .addValue("requestId", entry.getRequestId())
                .addValue("type", entry.getType().name())
                .addValue("method", entry.getMethod())
                .addValue("path", entry.getPath())
                .addValue("requestHeaders", entry.getRequestHeaders())
                .addValue("requestBody", entry.getRequestBody())
                .addValue("responseStatus", entry.getResponseStatus())
                .addValue("responseBody", entry.getResponseBody())
                .addValue("durationMs", entry.getDurationMs())
                .addValue("mode", entry.getMode() != null ? entry.getMode().name() : null)
                .addValue("traceId", entry.getTraceId())
                .addValue("createdAt", Timestamp.from(entry.getCreatedAt())));
    }

    public Page<GatewayLogResponse> list(UUID merchantId, String type, String mode, Pageable pageable) {
        ApiKeyMode modeEnum = (mode != null && !mode.isBlank()) ? ApiKeyMode.valueOf(mode.toUpperCase()) : null;
        GatewayLogType logType = (type != null && !type.isBlank()) ? GatewayLogType.valueOf(type.toUpperCase()) : null;

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("merchantId", merchantId)
                .addValue("limit", pageable.getPageSize())
                .addValue("offset", pageable.getOffset());
        List<String> predicates = new ArrayList<>();
        predicates.add("merchant_id = :merchantId");
        if (logType != null) {
            predicates.add("type = :type");
            parameters.addValue("type", logType.name());
        }
        if (modeEnum != null) {
            predicates.add("(mode = :mode OR mode IS NULL)");
            parameters.addValue("mode", modeEnum.name());
        }

        String whereClause = String.join(" AND ", predicates);
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM gateway_logs WHERE " + whereClause,
                parameters,
                Long.class);
        List<GatewayLogResponse> content = jdbcTemplate.query("""
                SELECT id, merchant_id, api_key_id, request_id, type, method, path,
                       request_headers, request_body, response_status, response_body,
                       duration_ms, created_at
                FROM gateway_logs
                WHERE %s
                ORDER BY created_at DESC
                LIMIT :limit OFFSET :offset
                """.formatted(whereClause), parameters, (rs, rowNum) -> mapResponse(rs));
        return new PageImpl<>(content, pageable, total != null ? total : 0);
    }

    private GatewayLogResponse mapResponse(ResultSet rs) throws SQLException {
        return new GatewayLogResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("api_key_id", UUID.class),
                rs.getString("request_id"),
                rs.getString("type"),
                rs.getString("method"),
                rs.getString("path"),
                rs.getString("request_headers"),
                rs.getString("request_body"),
                (Integer) rs.getObject("response_status"),
                rs.getString("response_body"),
                (Long) rs.getObject("duration_ms"),
                rs.getTimestamp("created_at").toInstant()
        );
    }
}
