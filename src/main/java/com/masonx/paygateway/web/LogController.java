package com.masonx.paygateway.web;

import com.masonx.paygateway.service.GatewayLogService;
import com.masonx.paygateway.web.dto.GatewayLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/logs")
public class LogController {

    private final GatewayLogService gatewayLogService;

    public LogController(GatewayLogService gatewayLogService) {
        this.gatewayLogService = gatewayLogService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'LOG', 'READ')")
    public ResponseEntity<Page<GatewayLogResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String type,
            @PageableDefault(size = 50, sort = "createdAt") Pageable pageable) {
        return ResponseEntity.ok(gatewayLogService.list(merchantId, type, pageable));
    }
}
