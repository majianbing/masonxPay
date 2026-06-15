package com.masonx.paygateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Phase 2.2 — Trace ID propagation.
 *
 * Runs before all other filters. Accepts X-Request-Id from the caller (e.g. the dashboard
 * or API client) or generates a fresh UUID. Stores the value in:
 *   - SLF4J MDC as "traceId" — appears in every log line for the duration of the request
 *   - Response header X-Request-Id — echoed back so clients can correlate
 *
 * Downstream code (ApiRequestLoggingFilter, PaymentIntentService) reads MDC to propagate
 * the trace ID into gateway_logs.trace_id and payment_intents.trace_id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY     = "traceId";
    public static final String HEADER_NAME = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER_NAME);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER_NAME, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
