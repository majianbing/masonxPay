package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.web.dto.RefundResponse;
import com.masonx.paygateway.service.RefundService;
import com.masonx.paygateway.web.dto.CreateRefundRequest;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import com.masonx.paygateway.web.dto.RefundResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Dashboard-facing (JWT-authenticated) read/refund endpoints for payment intents.
 * SDK-facing create/confirm/cancel live in PaymentIntentController (API-key auth).
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/payment-intents")
public class DashboardPaymentController {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final RefundRepository refundRepository;
    private final RefundService refundService;
    private final ObjectMapper objectMapper;

    public DashboardPaymentController(PaymentIntentRepository paymentIntentRepository,
                                      PaymentRequestRepository paymentRequestRepository,
                                      RefundRepository refundRepository,
                                      RefundService refundService,
                                      ObjectMapper objectMapper) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.refundRepository = refundRepository;
        this.refundService = refundService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<Page<PaymentIntentResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        ApiKeyMode modeEnum = (mode != null && !mode.isBlank()) ? ApiKeyMode.valueOf(mode.toUpperCase()) : null;
        Page<PaymentIntent> page;
        if (modeEnum != null && status != null && !status.isBlank()) {
            PaymentIntentStatus statusEnum = PaymentIntentStatus.valueOf(status.toUpperCase());
            page = paymentIntentRepository.findByMerchantIdAndStatusAndMode(merchantId, statusEnum, modeEnum, pageable);
        } else if (modeEnum != null) {
            page = paymentIntentRepository.findByMerchantIdAndMode(merchantId, modeEnum, pageable);
        } else if (status != null && !status.isBlank()) {
            PaymentIntentStatus statusEnum = PaymentIntentStatus.valueOf(status.toUpperCase());
            page = paymentIntentRepository.findByMerchantIdAndStatus(merchantId, statusEnum, pageable);
        } else {
            page = paymentIntentRepository.findByMerchantId(merchantId, pageable);
        }

        return ResponseEntity.ok(page.map(intent -> {
            List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
            return PaymentIntentResponse.from(intent, attempts, objectMapper);
        }));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<PaymentIntentResponse> get(
            @PathVariable UUID merchantId,
            @PathVariable UUID id) {

        PaymentIntent intent = paymentIntentRepository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found"));

        List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
        return ResponseEntity.ok(PaymentIntentResponse.from(intent, attempts, objectMapper));
    }

    @GetMapping("/refunds")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'REFUND', 'READ')")
    public ResponseEntity<Page<RefundResponse>> listRefunds(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String mode,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        ApiKeyMode modeEnum = (mode != null && !mode.isBlank()) ? ApiKeyMode.valueOf(mode.toUpperCase()) : ApiKeyMode.TEST;
        return ResponseEntity.ok(
                refundRepository.findByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, modeEnum, pageable)
                        .map(RefundResponse::from));
    }

    @PostMapping("/{id}/refunds")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'REFUND', 'CREATE')")
    public ResponseEntity<RefundResponse> refund(
            @PathVariable UUID merchantId,
            @PathVariable UUID id,
            @Valid @RequestBody CreateRefundRequest req) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(refundService.createRefund(merchantId, id, req));
    }
}
