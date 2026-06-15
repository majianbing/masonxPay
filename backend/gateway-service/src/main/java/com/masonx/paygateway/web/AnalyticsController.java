package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.service.AnalyticsService;
import com.masonx.paygateway.web.dto.AnalyticsResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<AnalyticsResponse> get(
            @PathVariable UUID merchantId,
            @RequestParam(defaultValue = "TEST") String mode,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "status") String groupBy) {

        ApiKeyMode resolvedMode;
        try {
            resolvedMode = ApiKeyMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid mode: " + mode);
        }

        LocalDate toDate = to != null ? LocalDate.parse(to) : LocalDate.now(ZoneOffset.UTC);
        LocalDate fromDate = from != null ? LocalDate.parse(from) : toDate.minusDays(29);

        AnalyticsResponse response = analyticsService.getAnalytics(
                merchantId, resolvedMode, fromDate, toDate, groupBy);
        return ResponseEntity.ok(response);
    }
}
