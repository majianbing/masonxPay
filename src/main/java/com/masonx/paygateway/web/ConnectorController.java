package com.masonx.paygateway.web;

import com.masonx.paygateway.service.ProviderAccountService;
import com.masonx.paygateway.web.dto.CreateProviderAccountRequest;
import com.masonx.paygateway.web.dto.ProviderAccountResponse;
import com.masonx.paygateway.web.dto.UpdateProviderAccountRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/connectors")
public class ConnectorController {

    private final ProviderAccountService service;

    public ConnectorController(ProviderAccountService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'READ')")
    public ResponseEntity<List<ProviderAccountResponse>> list(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(service.list(merchantId));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'CREATE')")
    public ResponseEntity<ProviderAccountResponse> create(@PathVariable UUID merchantId,
                                                          @Valid @RequestBody CreateProviderAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(merchantId, req));
    }

    @PatchMapping("/{accountId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'UPDATE')")
    public ResponseEntity<ProviderAccountResponse> update(@PathVariable UUID merchantId,
                                                          @PathVariable UUID accountId,
                                                          @Valid @RequestBody UpdateProviderAccountRequest req) {
        return ResponseEntity.ok(service.update(merchantId, accountId, req));
    }

    @DeleteMapping("/{accountId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'DELETE')")
    public ResponseEntity<Void> delete(@PathVariable UUID merchantId, @PathVariable UUID accountId) {
        service.delete(merchantId, accountId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{accountId}/set-primary")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CONNECTOR', 'UPDATE')")
    public ResponseEntity<ProviderAccountResponse> setPrimary(@PathVariable UUID merchantId,
                                                               @PathVariable UUID accountId) {
        return ResponseEntity.ok(service.setPrimary(merchantId, accountId));
    }
}
