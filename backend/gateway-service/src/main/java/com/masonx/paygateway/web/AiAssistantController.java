package com.masonx.paygateway.web;

import com.masonx.paygateway.service.AiAssistantService;
import com.masonx.paygateway.web.dto.RagAnswerRequest;
import com.masonx.paygateway.web.dto.RagAnswerResponse;
import com.masonx.paygateway.web.dto.RagIndexStatusResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/assistant")
public class AiAssistantController {

    private final AiAssistantService service;

    public AiAssistantController(AiAssistantService service) {
        this.service = service;
    }

    @PostMapping("/questions")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<RagAnswerResponse> answer(@PathVariable UUID merchantId,
                                                    @Valid @RequestBody RagAnswerRequest request) {
        return ResponseEntity.ok(service.answer(merchantId, request));
    }

    @GetMapping("/status")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<RagIndexStatusResponse> status(@PathVariable UUID merchantId) {
        return ResponseEntity.ok(service.status(merchantId));
    }
}
