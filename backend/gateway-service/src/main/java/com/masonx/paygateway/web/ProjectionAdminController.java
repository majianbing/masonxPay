package com.masonx.paygateway.web;

import com.masonx.paygateway.projection.PaymentProjectionBackfillService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/admin/payment-projection")
@ConditionalOnBean(PaymentProjectionBackfillService.class)
public class ProjectionAdminController {

    private final PaymentProjectionBackfillService backfillService;

    public ProjectionAdminController(PaymentProjectionBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/backfill")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<Map<String, Object>> triggerBackfill() {
        CompletableFuture.runAsync(() -> backfillService.triggerBackfill());
        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "message", "Backfill started in background; check application logs for progress."));
    }
}
