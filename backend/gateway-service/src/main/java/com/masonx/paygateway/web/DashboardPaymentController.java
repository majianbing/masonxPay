package com.masonx.paygateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccount;
import com.masonx.paygateway.domain.projection.PaymentReadModel;
import com.masonx.paygateway.domain.projection.PaymentReadModelRepository;
import com.masonx.paygateway.domain.projection.ProjectionEventStatus;
import com.masonx.paygateway.domain.projection.ProjectionProcessedEvent;
import com.masonx.paygateway.domain.projection.ProjectionProcessedEventRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Value;
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
    private final PaymentReadModelRepository paymentReadModelRepository;
    private final PaymentRequestRepository paymentRequestRepository;
    private final RefundRepository refundRepository;
    private final RefundService refundService;
    private final ObjectMapper objectMapper;
    private final com.masonx.paygateway.domain.connector.ProviderAccountRepository providerAccountRepository;
    private final ProjectionProcessedEventRepository projectionProcessedEventRepository;
    private final boolean paymentProjectionEnabled;

    public DashboardPaymentController(PaymentIntentRepository paymentIntentRepository,
                                      PaymentReadModelRepository paymentReadModelRepository,
                                      PaymentRequestRepository paymentRequestRepository,
                                      RefundRepository refundRepository,
                                      RefundService refundService,
                                      ObjectMapper objectMapper,
                                      com.masonx.paygateway.domain.connector.ProviderAccountRepository providerAccountRepository,
                                      ProjectionProcessedEventRepository projectionProcessedEventRepository,
                                      @Value("${app.kafka.payment-projection.enabled:false}") boolean paymentProjectionEnabled) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentReadModelRepository = paymentReadModelRepository;
        this.paymentRequestRepository = paymentRequestRepository;
        this.refundRepository = refundRepository;
        this.refundService = refundService;
        this.objectMapper = objectMapper;
        this.providerAccountRepository = providerAccountRepository;
        this.projectionProcessedEventRepository = projectionProcessedEventRepository;
        this.paymentProjectionEnabled = paymentProjectionEnabled;
    }

    @GetMapping
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<Page<PaymentIntentResponse>> list(
            @PathVariable UUID merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String labelSearch,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @PageableDefault(size = 20, sort = "sourceCreatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        if (!paymentProjectionEnabled) {
            return ResponseEntity.ok(listFromPaymentIntents(
                    merchantId, status, mode, search, provider, method, labelSearch, dateFrom, dateTo, pageable));
        }

        Specification<PaymentReadModel> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("merchantId"), merchantId));

            if (mode != null && !mode.isBlank())
                predicates.add(cb.equal(root.get("mode"), mode.toUpperCase()));
            if (status != null && !status.isBlank())
                predicates.add(cb.equal(root.get("status"), status.toUpperCase()));
            if (provider != null && !provider.isBlank())
                predicates.add(cb.equal(root.get("resolvedProvider"), provider.toUpperCase()));
            if (method != null && !method.isBlank())
                predicates.add(cb.equal(cb.lower(root.get("paymentMethodType")), method.toLowerCase()));
            if (search != null && !search.isBlank())
                predicates.add(cb.like(cb.lower(root.get("searchText")), "%" + search.toLowerCase() + "%"));
            if (labelSearch != null && !labelSearch.isBlank()) {
                Subquery<UUID> sub = query.subquery(UUID.class);
                Root<ProviderAccount> pa = sub.from(ProviderAccount.class);
                sub.select(pa.get("id")).where(cb.and(
                        cb.equal(pa.get("id"), root.get("connectorAccountId")),
                        cb.like(cb.lower(pa.get("label")), "%" + labelSearch.toLowerCase() + "%")));
                predicates.add(cb.exists(sub));
            }
            if (dateFrom != null && !dateFrom.isBlank())
                predicates.add(cb.greaterThanOrEqualTo(root.get("sourceCreatedAt"),
                        LocalDate.parse(dateFrom).atStartOfDay(ZoneOffset.UTC).toInstant()));
            if (dateTo != null && !dateTo.isBlank())
                predicates.add(cb.lessThanOrEqualTo(root.get("sourceCreatedAt"),
                        LocalDate.parse(dateTo).atTime(23, 59, 59).toInstant(ZoneOffset.UTC)));

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<PaymentReadModel> page = paymentReadModelRepository.findAll(spec, projectionPageable(pageable));

        // Bulk-fetch connector account labels to avoid N+1
        Set<UUID> accountIds = page.stream()
                .map(PaymentReadModel::getConnectorAccountId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, String> accountLabels = providerAccountRepository.findAllById(accountIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProviderAccount::getId,
                        ProviderAccount::getLabel));

        return ResponseEntity.ok(page.map(model -> toPaymentIntentResponse(model,
                model.getConnectorAccountId() != null ? accountLabels.get(model.getConnectorAccountId()) : null)));
    }

    private Page<PaymentIntentResponse> listFromPaymentIntents(UUID merchantId,
                                                               String status,
                                                               String mode,
                                                               String search,
                                                               String provider,
                                                               String method,
                                                               String labelSearch,
                                                               String dateFrom,
                                                               String dateTo,
                                                               Pageable pageable) {
        Specification<PaymentIntent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("merchantId"), merchantId));

            if (mode != null && !mode.isBlank())
                predicates.add(cb.equal(root.get("mode"), ApiKeyMode.valueOf(mode.toUpperCase())));
            if (status != null && !status.isBlank())
                predicates.add(cb.equal(root.get("status"), PaymentIntentStatus.valueOf(status.toUpperCase())));
            if (provider != null && !provider.isBlank())
                predicates.add(cb.equal(root.get("resolvedProvider"), PaymentProvider.valueOf(provider.toUpperCase())));
            if (method != null && !method.isBlank())
                predicates.add(cb.equal(cb.lower(root.get("paymentMethodType")), method.toLowerCase()));
            if (search != null && !search.isBlank()) {
                String term = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("id").as(String.class)), term),
                        cb.like(cb.lower(root.get("providerPaymentId")), term),
                        cb.like(cb.lower(root.get("orderId")), term),
                        cb.like(cb.lower(root.get("description")), term)));
            }
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

        Page<PaymentIntent> page = paymentIntentRepository.findAll(spec, intentPageable(pageable));
        Set<UUID> accountIds = page.stream()
                .map(PaymentIntent::getConnectorAccountId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Map<UUID, String> accountLabels = providerAccountRepository.findAllById(accountIds)
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProviderAccount::getId,
                        ProviderAccount::getLabel));

        return page.map(intent -> PaymentIntentResponse.from(
                intent,
                List.of(),
                objectMapper,
                intent.getConnectorAccountId() != null ? accountLabels.get(intent.getConnectorAccountId()) : null));
    }

    private PaymentIntentResponse toPaymentIntentResponse(PaymentReadModel model, String connectorAccountLabel) {
        return new PaymentIntentResponse(
                model.getPaymentIntentId(),
                model.getMerchantId(),
                model.getMode(),
                model.getAmount(),
                model.getCurrency(),
                model.getStatus(),
                model.getCaptureMethod(),
                model.getResolvedProvider(),
                model.getConnectorAccountId(),
                connectorAccountLabel,
                model.getProviderPaymentId(),
                model.getIdempotencyKey(),
                model.getOrderId(),
                model.getDescription(),
                model.getBillingEmail() != null
                        ? new BillingDetails(null, null, model.getBillingEmail(), null, null)
                        : null,
                null,
                null,
                null,
                null,
                null,
                null,
                model.getSourceCreatedAt(),
                model.getSourceUpdatedAt(),
                model.getPaymentMethodType() != null
                        ? List.of(new PaymentIntentResponse.PaymentAttemptSummary(
                                null, 1, null, model.getConnectorAccountId(),
                                model.getPaymentMethodType(), model.getStatus(),
                                null, null, null, model.getSourceCreatedAt()))
                        : List.of());
    }

    private Pageable projectionPageable(Pageable pageable) {
        Sort mapped = Sort.by(pageable.getSort().stream()
                .map(order -> new Sort.Order(order.getDirection(), projectionSortProperty(order.getProperty())))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), mapped);
    }

    private Pageable intentPageable(Pageable pageable) {
        Sort mapped = Sort.by(pageable.getSort().stream()
                .map(order -> new Sort.Order(order.getDirection(), intentSortProperty(order.getProperty())))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), mapped);
    }

    private String intentSortProperty(String property) {
        return switch (property) {
            case "sourceCreatedAt" -> "createdAt";
            case "sourceUpdatedAt" -> "updatedAt";
            case "paymentIntentId" -> "id";
            default -> property;
        };
    }

    private String projectionSortProperty(String property) {
        return switch (property) {
            case "createdAt" -> "sourceCreatedAt";
            case "updatedAt" -> "sourceUpdatedAt";
            case "id" -> "paymentIntentId";
            default -> property;
        };
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

    @GetMapping("/projection-health")
    @PreAuthorize("@permissionEvaluator.hasPermission(authentication, #merchantId, 'PAYMENT', 'READ')")
    public ResponseEntity<Map<String, Object>> projectionHealth(@PathVariable UUID merchantId) {
        if (!paymentProjectionEnabled) {
            return ResponseEntity.ok(Map.of("enabled", false));
        }
        long failedCount = projectionProcessedEventRepository.countByStatus(ProjectionEventStatus.FAILED);
        long oldestFailedAgeSecs = projectionProcessedEventRepository
                .findFirstByStatusOrderByProcessedAtAsc(ProjectionEventStatus.FAILED)
                .map(ProjectionProcessedEvent::getProcessedAt)
                .map(t -> java.time.Duration.between(t, java.time.Instant.now()).getSeconds())
                .orElse(0L);
        return ResponseEntity.ok(Map.of(
                "enabled", true,
                "failedCount", failedCount,
                "oldestFailedAgeSecs", oldestFailedAgeSecs));
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
