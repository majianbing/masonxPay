package com.masonx.paygateway.web;

import com.masonx.paygateway.service.RoutingRuleService;
import com.masonx.paygateway.web.dto.CreateRoutingRuleRequest;
import com.masonx.paygateway.web.dto.RoutingRuleResponse;
import com.masonx.paygateway.web.dto.UpdateRoutingRuleRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/routing-rules")
public class RoutingRuleController {

    private final RoutingRuleService routingRuleService;

    public RoutingRuleController(RoutingRuleService routingRuleService) {
        this.routingRuleService = routingRuleService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'READ')")
    public ResponseEntity<List<RoutingRuleResponse>> list(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(routingRuleService.list(merchantId));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'CREATE')")
    public ResponseEntity<RoutingRuleResponse> create(@PathVariable UUID merchantId,
                                                       @Valid @RequestBody CreateRoutingRuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routingRuleService.create(merchantId, req));
    }

    @PutMapping("/{ruleId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'UPDATE')")
    public ResponseEntity<RoutingRuleResponse> update(@PathVariable UUID merchantId,
                                                       @PathVariable UUID ruleId,
                                                       @Valid @RequestBody UpdateRoutingRuleRequest req) {
        return ResponseEntity.ok(routingRuleService.update(merchantId, ruleId, req));
    }

    @DeleteMapping("/{ruleId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'ROUTING_RULE', 'DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID merchantId, @PathVariable UUID ruleId) {
        routingRuleService.delete(merchantId, ruleId);
        return ResponseEntity.noContent().build();
    }
}
