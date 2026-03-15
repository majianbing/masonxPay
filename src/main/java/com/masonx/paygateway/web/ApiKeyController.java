package com.masonx.paygateway.web;

import com.masonx.paygateway.service.ApiKeyService;
import com.masonx.paygateway.web.dto.ApiKeyPairResponse;
import com.masonx.paygateway.web.dto.ApiKeyResponse;
import com.masonx.paygateway.web.dto.CreateApiKeyRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'API_KEY', 'READ')")
    public ResponseEntity<List<ApiKeyResponse>> list(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(apiKeyService.list(merchantId));
    }

    @PostMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'API_KEY', 'CREATE')")
    public ResponseEntity<ApiKeyPairResponse> create(@PathVariable UUID merchantId,
                                                      @Valid @RequestBody CreateApiKeyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(apiKeyService.create(merchantId, req));
    }

    @DeleteMapping("/{keyId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'API_KEY', 'DELETE')")
    public ResponseEntity<Void> revoke(@PathVariable UUID merchantId,
                                        @PathVariable UUID keyId) {
        apiKeyService.revoke(merchantId, keyId);
        return ResponseEntity.noContent().build();
    }
}
