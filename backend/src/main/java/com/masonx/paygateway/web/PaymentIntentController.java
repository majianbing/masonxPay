package com.masonx.paygateway.web;

import com.masonx.paygateway.domain.payment.Refund;
import com.masonx.paygateway.domain.payment.RefundRepository;
import com.masonx.paygateway.security.apikey.ApiKeyAuthentication;
import com.masonx.paygateway.service.PaymentIntentService;
import com.masonx.paygateway.service.RefundService;
import com.masonx.paygateway.web.dto.ConfirmPaymentIntentRequest;
import com.masonx.paygateway.web.dto.CreatePaymentIntentRequest;
import com.masonx.paygateway.web.dto.CreateRefundRequest;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import com.masonx.paygateway.web.dto.RefundResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/payment-intents")
public class PaymentIntentController {

    private final PaymentIntentService paymentIntentService;
    private final RefundService refundService;
    private final RefundRepository refundRepository;

    public PaymentIntentController(PaymentIntentService paymentIntentService,
                                    RefundService refundService,
                                    RefundRepository refundRepository) {
        this.paymentIntentService = paymentIntentService;
        this.refundService = refundService;
        this.refundRepository = refundRepository;
    }

    @PostMapping
    public ResponseEntity<PaymentIntentResponse> create(Authentication auth,
                                                         @Valid @RequestBody CreatePaymentIntentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentIntentService.create(apiKey(auth), req));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentIntentResponse> get(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok(paymentIntentService.get(apiKey(auth), id));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<PaymentIntentResponse> confirm(Authentication auth,
                                                          @PathVariable UUID id,
                                                          @Valid @RequestBody ConfirmPaymentIntentRequest req) {
        return ResponseEntity.ok(paymentIntentService.confirm(apiKey(auth), id, req));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentIntentResponse> cancel(Authentication auth, @PathVariable UUID id) {
        return ResponseEntity.ok(paymentIntentService.cancel(apiKey(auth), id));
    }

    @PostMapping("/{id}/refunds")
    public ResponseEntity<RefundResponse> createRefund(Authentication auth,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody CreateRefundRequest req) {
        ApiKeyAuthentication ak = apiKey(auth);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(refundService.createRefund(ak.getMerchantId(), id, req));
    }

    @GetMapping("/{id}/refunds")
    public ResponseEntity<List<RefundResponse>> listRefunds(Authentication auth, @PathVariable UUID id) {
        apiKey(auth); // validate auth
        List<RefundResponse> refunds = refundRepository.findByPaymentIntentId(id)
                .stream().map(RefundResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(refunds);
    }

    private ApiKeyAuthentication apiKey(Authentication auth) {
        if (!(auth instanceof ApiKeyAuthentication ak)) {
            throw new AccessDeniedException("API key required");
        }
        return ak;
    }
}
