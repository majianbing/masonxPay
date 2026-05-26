package com.masonx.paygateway.web;

import com.masonx.paygateway.service.routing.ProviderAccountCapabilityManagementService;
import com.masonx.paygateway.web.dto.ProviderAccountCapabilityRequest;
import com.masonx.paygateway.web.dto.ProviderAccountCapabilityResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/connectors/{accountId}/capabilities")
public class ProviderAccountCapabilityController {

    private final ProviderAccountCapabilityManagementService service;

    public ProviderAccountCapabilityController(ProviderAccountCapabilityManagementService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'READ')")
    public ResponseEntity<List<ProviderAccountCapabilityResponse>> list(@PathVariable UUID merchantId,
                                                                        @PathVariable UUID accountId) {
        return ResponseEntity.ok(service.list(merchantId, accountId));
    }

    @PutMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'UPDATE')")
    public ResponseEntity<List<ProviderAccountCapabilityResponse>> replace(
            @PathVariable UUID merchantId,
            @PathVariable UUID accountId,
            @Valid @RequestBody List<ProviderAccountCapabilityRequest> request) {
        return ResponseEntity.ok(service.replace(merchantId, accountId, request));
    }
}
