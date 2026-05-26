package com.masonx.paygateway.web;

import com.masonx.paygateway.service.routing.RoutePolicyService;
import com.masonx.paygateway.web.dto.RoutePolicyAuditLogResponse;
import com.masonx.paygateway.web.dto.RoutePolicyResponse;
import com.masonx.paygateway.web.dto.RoutePolicyUpsertRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/route-policies")
public class RoutePolicyController {

    private final RoutePolicyService service;

    public RoutePolicyController(RoutePolicyService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'READ')")
    public ResponseEntity<List<RoutePolicyResponse>> list(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(service.list(merchantId));
    }

    @GetMapping("/{policyId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'READ')")
    public ResponseEntity<RoutePolicyResponse> get(@PathVariable UUID merchantId,
                                                   @PathVariable UUID policyId) {
        return ResponseEntity.ok(service.get(merchantId, policyId));
    }

    @GetMapping("/{policyId}/audit-logs")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'READ')")
    public ResponseEntity<List<RoutePolicyAuditLogResponse>> auditLogs(@PathVariable UUID merchantId,
                                                                       @PathVariable UUID policyId) {
        return ResponseEntity.ok(service.auditLogs(merchantId, policyId));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'CREATE')")
    public ResponseEntity<RoutePolicyResponse> createDraft(@PathVariable UUID merchantId,
                                                           @Valid @RequestBody RoutePolicyUpsertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDraft(merchantId, request));
    }

    @PutMapping("/{policyId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'UPDATE')")
    public ResponseEntity<RoutePolicyResponse> replaceDraft(@PathVariable UUID merchantId,
                                                            @PathVariable UUID policyId,
                                                            @Valid @RequestBody RoutePolicyUpsertRequest request) {
        return ResponseEntity.ok(service.replaceDraft(merchantId, policyId, request));
    }

    @PostMapping("/{policyId}/publish")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'UPDATE')")
    public ResponseEntity<RoutePolicyResponse> publish(@PathVariable UUID merchantId,
                                                       @PathVariable UUID policyId) {
        return ResponseEntity.ok(service.publish(merchantId, policyId));
    }

    @PostMapping("/{policyId}/archive")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'UPDATE')")
    public ResponseEntity<RoutePolicyResponse> archive(@PathVariable UUID merchantId,
                                                       @PathVariable UUID policyId) {
        return ResponseEntity.ok(service.archive(merchantId, policyId));
    }
}
