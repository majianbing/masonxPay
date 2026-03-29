package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.payment.*;
import com.masonx.paygateway.web.dto.RefundResponse;
import com.masonx.paygateway.service.RefundService;
import com.masonx.paygateway.web.dto.CreateRefundRequest;
import com.masonx.paygateway.web.dto.PaymentIntentResponse;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final com.masonx.paygateway.domain.connector.ProviderAccountRepository providerAccountRepository;

    public DashboardPaymentController(PaymentIntentRepository paymentIntentRepository,
                                      PaymentRequestRepository paymentRequestRepository,
                                      RefundRepository refundRepository,
                                      RefundService refundService,
                                      ObjectMapper objectMapper,
                                      com.masonx.paygateway.domain.connector.ProviderAccountRepository providerAccountRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.refundRepository = refundRepository;
        this.refundService = refundService;
        this.objectMapper = objectMapper;
        this.providerAccountRepository = providerAccountRepository;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<Page<PaymentIntentResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String labelSearch,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Specification<PaymentIntent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("merchantId"), merchantId));

            if (mode != null && !mode.isBlank())
                predicates.add(cb.equal(root.get("mode"), ApiKeyMode.valueOf(mode.toUpperCase())));
            if (status != null && !status.isBlank())
                predicates.add(cb.equal(root.get("status"), PaymentIntentStatus.valueOf(status.toUpperCase())));
            if (provider != null && !provider.isBlank())
                predicates.add(cb.equal(root.get("resolvedProvider"), PaymentProvider.valueOf(provider.toUpperCase())));
            if (search != null && !search.isBlank())
                predicates.add(cb.like(root.get("id").as(String.class), search.toLowerCase() + "%"));
            if (labelSearch != null && !labelSearch.isBlank()) {
                Subquery<UUID> sub = query.subquery(UUID.class);
                Root<ProviderAccount> pa = sub.from(ProviderAccount.class);
                sub.select(pa.get("id")).where(cb.and(
                        cb.equal(pa.get("id"), root.get("connectorAccountId")),
                        cb.like(cb.lower(pa.get("label")), "%" + labelSearch.toLowerCase() + "%")));
                predicates.add(cb.exists(sub));
            }
            if (dateFrom != null && !dateFrom.isBlank())
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        LocalDate.parse(dateFrom).atStartOfDay(ZoneOffset.UTC).toInstant()));
            if (dateTo != null && !dateTo.isBlank())
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"),
                        LocalDate.parse(dateTo).atTime(23, 59, 59).toInstant(ZoneOffset.UTC)));

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<PaymentIntent> page = paymentIntentRepository.findAll(spec, pageable);

        // Bulk-fetch connector account labels to avoid N+1
        Set<UUID> accountIds = page.stream()
                .map(PaymentIntent::getConnectorAccountId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, String> accountLabels = providerAccountRepository.findAllById(accountIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProviderAccount::getId,
                        ProviderAccount::getLabel));

        return ResponseEntity.ok(page.map(intent -> {
            List<PaymentRequest> attempts = paymentRequestRepository.findByPaymentIntentId(intent.getId());
            String label = intent.getConnectorAccountId() != null
                    ? accountLabels.get(intent.getConnectorAccountId()) : null;
            return PaymentIntentResponse.from(intent, attempts, objectMapper, label);
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
        String label = intent.getConnectorAccountId() != null
                ? providerAccountRepository.findById(intent.getConnectorAccountId())
                        .map(ProviderAccount::getLabel).orElse(null)
                : null;
        return ResponseEntity.ok(PaymentIntentResponse.from(intent, attempts, objectMapper, label));
    }

    @GetMapping("/refunds")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'REFUND', 'READ')")
    public ResponseEntity<Page<RefundResponse>> listRefunds(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Specification<Refund> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("merchantId"), merchantId));

            ApiKeyMode modeEnum = (mode != null && !mode.isBlank()) ? ApiKeyMode.valueOf(mode.toUpperCase()) : ApiKeyMode.TEST;
            predicates.add(cb.equal(root.get("mode"), modeEnum));

            if (search != null && !search.isBlank()) {
                String term = search.toLowerCase();
                predicates.add(cb.or(
                        cb.like(root.get("id").as(String.class), term + "%"),
                        cb.like(root.get("paymentIntentId").as(String.class), term + "%")));
            }
            if (dateFrom != null && !dateFrom.isBlank())
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"),
                        LocalDate.parse(dateFrom).atStartOfDay(ZoneOffset.UTC).toInstant()));
            if (dateTo != null && !dateTo.isBlank())
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"),
                        LocalDate.parse(dateTo).atTime(23, 59, 59).toInstant(ZoneOffset.UTC)));

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return ResponseEntity.ok(refundRepository.findAll(spec, pageable).map(RefundResponse::from));
    }

    @GetMapping("/{id}/refunds")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'REFUND', 'READ')")
    public ResponseEntity<List<RefundResponse>> listRefundsForPayment(
            @PathVariable UUID merchantId,
            @PathVariable UUID id) {

        paymentIntentRepository.findByIdAndMerchantId(id, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("PaymentIntent not found"));

        return ResponseEntity.ok(
                refundRepository.findByPaymentIntentId(id)
                        .stream().map(RefundResponse::from).toList());
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
