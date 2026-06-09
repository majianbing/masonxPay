package com.masonx.paygateway.web;

import com.masonx.paygateway.service.MerchantAuditLogService;
import com.masonx.paygateway.web.dto.MerchantAuditLogResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/audit-logs")
public class MerchantAuditLogController {

    private final MerchantAuditLogService auditLogService;

    public MerchantAuditLogController(MerchantAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'LOG', 'READ')")
    public ResponseEntity<Page<MerchantAuditLogResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String action,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(auditLogService.list(merchantId, action, pageable));
    }
}
