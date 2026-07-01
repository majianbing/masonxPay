package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.retry.ScheduledRetryStatus;
import com.masonx.paygateway.service.retry.ScheduledRetryService;
import com.masonx.paygateway.web.dto.ScheduledRetryJobResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/scheduled-retries")
public class ScheduledRetryController {

    private final ScheduledRetryService service;

    public ScheduledRetryController(ScheduledRetryService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<List<ScheduledRetryJobResponse>> list(@PathVariable UUID merchantId,
                                                                @RequestParam(required = false) ScheduledRetryStatus status) {
        return ResponseEntity.ok(service.list(merchantId, status).stream()
                .map(ScheduledRetryJobResponse::from)
                .toList());
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'EXECUTE')")
    public ResponseEntity<ScheduledRetryJobResponse> cancel(@PathVariable UUID merchantId,
                                                            @PathVariable String jobId) {
        return ResponseEntity.ok(ScheduledRetryJobResponse.from(service.cancel(merchantId, jobId)));
    }
}
