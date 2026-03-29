package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.log.GatewayLog;
import com.masonx.paygateway.domain.log.GatewayLogType;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import com.masonx.paygateway.service.GatewayLogService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class ApiRequestLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_BYTES = 4096;

    /**
     * JSON field names whose values must never appear in stored logs.
     * Matched case-insensitively against the key token in the JSON string.
     */
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "password", "passwordhash",
            "secretkey", "accesstoken", "privatekey", "publickey",
            "refreshtoken",
            "mfasecret", "mfabackupcodes", "mfasessiontoken", "code",
            "btprivatekey", "btpublickey"
    );

    /**
     * Replaces the values of known sensitive JSON fields with "[REDACTED]".
     * Handles both quoted-string and number values. Non-JSON bodies are returned unchanged.
     */
    private static final Pattern SENSITIVE_FIELD_PATTERN = buildSensitivePattern();

    private static Pattern buildSensitivePattern() {
        String fields = String.join("|", SENSITIVE_FIELDS);
        // Matches: "fieldName" (any case) : ("value" | number)
        return Pattern.compile(
                "(?i)(\"(?:" + fields + ")\"\\s*:\\s*)(?:\"[^\"]*\"|\\d+)",
                Pattern.CASE_INSENSITIVE);
    }

    private static String redactSensitiveFields(String body) {
        if (body == null || body.isBlank() || !body.stripLeading().startsWith("{")) return body;
        return SENSITIVE_FIELD_PATTERN.matcher(body).replaceAll("$1\"[REDACTED]\"");
    }

    private final GatewayLogService gatewayLogService;

    public ApiRequestLoggingFilter(GatewayLogService gatewayLogService) {
        this.gatewayLogService = gatewayLogService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only log API calls, not auth or admin endpoints
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedReq = new ContentCachingRequestWrapper(request, MAX_BODY_BYTES);
        ContentCachingResponseWrapper wrappedRes = new ContentCachingResponseWrapper(response);

        long start = System.currentTimeMillis();
        try {
            chain.doFilter(wrappedReq, wrappedRes);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            writeLog(wrappedReq, wrappedRes, durationMs);
            wrappedRes.copyBodyToResponse();
        }
    }

    private static final java.util.regex.Pattern MERCHANT_PATH =
            java.util.regex.Pattern.compile("/api/v1/merchants/([^/]+)/");

    private void writeLog(ContentCachingRequestWrapper req, ContentCachingResponseWrapper res, long durationMs) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        UUID merchantId = null;
        UUID apiKeyId = null;
        com.masonx.paygateway.domain.apikey.ApiKeyMode mode = null;
        if (auth instanceof ApiKeyAuthentication ak) {
            merchantId = ak.getMerchantId();
            apiKeyId = ak.getApiKeyId();
            mode = ak.getMode();
        } else if (auth != null && auth.isAuthenticated()) {
            // JWT-authenticated dashboard requests — extract merchant ID from URL path
            java.util.regex.Matcher m = MERCHANT_PATH.matcher(req.getRequestURI());
            if (m.find()) {
                try { merchantId = UUID.fromString(m.group(1)); } catch (IllegalArgumentException ignored) {}
            }
        }

        // Skip logging if we cannot associate the request with a merchant
        if (merchantId == null) return;

        GatewayLog log = new GatewayLog();
        log.setMerchantId(merchantId);
        log.setApiKeyId(apiKeyId);
        log.setMode(mode);
        log.setRequestId(req.getHeader("X-Request-Id"));
        log.setType(GatewayLogType.API_REQUEST);
        log.setMethod(req.getMethod());
        log.setPath(req.getRequestURI());
        log.setRequestHeaders(serializeHeaders(req));
        log.setRequestBody(redactSensitiveFields(bodyString(req.getContentAsByteArray())));
        log.setResponseStatus(res.getStatus());
        log.setResponseBody(redactSensitiveFields(bodyString(res.getContentAsByteArray())));
        log.setDurationMs(durationMs);

        gatewayLogService.log(log);
    }

    private String serializeHeaders(HttpServletRequest req) {
        StringBuilder sb = new StringBuilder();
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            // Redact Authorization header value
            if ("authorization".equalsIgnoreCase(name) || "x-publishable-key".equalsIgnoreCase(name)) {
                sb.append(name).append(": [REDACTED]\n");
            } else {
                sb.append(name).append(": ").append(req.getHeader(name)).append("\n");
            }
        }
        return sb.toString();
    }

    private String bodyString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        return new String(bytes, 0, Math.min(bytes.length, MAX_BODY_BYTES), StandardCharsets.UTF_8);
    }
}
