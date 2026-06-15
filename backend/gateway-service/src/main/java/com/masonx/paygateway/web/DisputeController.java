package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.dispute.DisputeStatus;
import com.masonx.paygateway.service.DisputeService;
import com.masonx.paygateway.web.dto.DisputeEvidenceFileResponse;
import com.masonx.paygateway.web.dto.DisputeEvidenceRequest;
import com.masonx.paygateway.web.dto.DisputeResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/disputes")
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CHARGEBACK', 'READ')")
    public ResponseEntity<Page<DisputeResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) DisputeStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(disputeService.list(merchantId, mode, status, pageable));
    }

    @GetMapping("/{disputeId}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CHARGEBACK', 'READ')")
    public ResponseEntity<DisputeResponse> get(
            @PathVariable UUID merchantId,
            @PathVariable UUID disputeId) {
        return ResponseEntity.ok(disputeService.get(merchantId, disputeId));
    }

    @PostMapping(value = "/{disputeId}/evidence-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CHARGEBACK', 'UPDATE')")
    public ResponseEntity<DisputeEvidenceFileResponse> uploadEvidenceFile(
            @PathVariable UUID merchantId,
            @PathVariable UUID disputeId,
            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(disputeService.uploadEvidenceFile(merchantId, disputeId, file));
    }

    @PostMapping("/{disputeId}/evidence")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'CHARGEBACK', 'UPDATE')")
    public ResponseEntity<DisputeResponse> submitEvidence(
            @PathVariable UUID merchantId,
            @PathVariable UUID disputeId,
            @RequestBody DisputeEvidenceRequest req) {
        return ResponseEntity.ok(disputeService.submitEvidence(merchantId, disputeId, req));
    }
}
