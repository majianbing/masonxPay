package com.masonx.paygateway.service.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.connector.ProviderAccountRepository;
import com.masonx.paygateway.domain.connector.ProviderAccountStatus;
import com.masonx.paygateway.domain.routing.*;
import com.masonx.paygateway.web.dto.RoutePolicyAuditLogResponse;
import com.masonx.paygateway.web.dto.RoutePolicyResponse;
import com.masonx.paygateway.web.dto.RoutePolicyUpsertRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class RoutePolicyService {

    private final RoutePolicyRepository policyRepository;
    private final RoutePolicyRouteRepository routeRepository;
    private final RoutePolicyStepRepository stepRepository;
    private final RoutePolicyAuditLogRepository auditLogRepository;
    private final RoutingAttributeRepository routingAttributeRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final RoutePolicyValidationService validationService;
    private final ObjectMapper objectMapper;

    public RoutePolicyService(RoutePolicyRepository policyRepository,
                              RoutePolicyRouteRepository routeRepository,
                              RoutePolicyStepRepository stepRepository,
                              RoutePolicyAuditLogRepository auditLogRepository,
                              RoutingAttributeRepository routingAttributeRepository,
                              ProviderAccountRepository providerAccountRepository,
                              RoutePolicyValidationService validationService,
                              ObjectMapper objectMapper) {
        this.policyRepository = policyRepository;
        this.routeRepository = routeRepository;
        this.stepRepository = stepRepository;
        this.auditLogRepository = auditLogRepository;
        this.routingAttributeRepository = routingAttributeRepository;
        this.providerAccountRepository = providerAccountRepository;
        this.validationService = validationService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<RoutePolicyResponse> list(UUID merchantId) {
        return policyRepository.findAllByMerchantIdOrderByUpdatedAtDesc(merchantId)
                .stream()
                .map(policy -> response(policy, List.of()))
                .toList();
    }

    public RoutePolicyResponse createDraft(UUID merchantId, RoutePolicyUpsertRequest request) {
        RoutePolicy policy = new RoutePolicy();
        policy.setMerchantId(merchantId);
        policy.setMode(ApiKeyMode.valueOf(request.mode().toUpperCase()));
        policy.setName(request.name());
        policy.setDescription(request.description());
        policy.setStatus(RoutePolicyStatus.DRAFT);
        policy.setPolicyVersion(nextVersion(merchantId, policy.getMode()));
        RoutePolicy saved = policyRepository.save(policy);
        replaceRoutes(merchantId, saved, request.routes());
        return validateAndRespond(saved);
    }

    public RoutePolicyResponse replaceDraft(UUID merchantId, UUID policyId, RoutePolicyUpsertRequest request) {
        RoutePolicy policy = loadOwned(merchantId, policyId);
        if (policy.getStatus() == RoutePolicyStatus.ACTIVE) {
            throw new IllegalStateException("Active route policies cannot be edited; create a new draft");
        }
        policy.setMode(ApiKeyMode.valueOf(request.mode().toUpperCase()));
        policy.setName(request.name());
        policy.setDescription(request.description());
        policy.setStatus(RoutePolicyStatus.DRAFT);
        RoutePolicy saved = policyRepository.save(policy);
        stepRepository.deleteAllByMerchantIdAndPolicyId(merchantId, saved.getId());
        routeRepository.deleteAllByMerchantIdAndPolicyId(merchantId, saved.getId());
        replaceRoutes(merchantId, saved, request.routes());
        return validateAndRespond(saved);
    }

    public RoutePolicyResponse publish(UUID merchantId, UUID policyId) {
        RoutePolicy policy = loadOwned(merchantId, policyId);
        List<RoutePolicyValidationIssue> issues = validate(policy);
        if (!issues.isEmpty()) {
            return response(policy, issues);
        }

        policyRepository.findByMerchantIdAndModeAndStatus(merchantId, policy.getMode(), RoutePolicyStatus.ACTIVE)
                .filter(active -> !active.getId().equals(policy.getId()))
                .ifPresent(active -> {
                    RoutePolicyStatus beforeStatus = active.getStatus();
                    String beforeState = auditState(active);
                    active.setStatus(RoutePolicyStatus.ARCHIVED);
                    RoutePolicy saved = policyRepository.save(active);
                    audit(saved, "AUTO_ARCHIVE_PREVIOUS", beforeStatus, saved.getStatus(), beforeState, auditState(saved));
                });

        RoutePolicyStatus beforeStatus = policy.getStatus();
        String beforeState = auditState(policy);
        policy.setStatus(RoutePolicyStatus.ACTIVE);
        policy.setPublishedAt(Instant.now());
        RoutePolicy saved = policyRepository.save(policy);
        audit(saved, "PUBLISH", beforeStatus, saved.getStatus(), beforeState, auditState(saved));
        return response(saved, List.of());
    }

    public RoutePolicyResponse archive(UUID merchantId, UUID policyId) {
        RoutePolicy policy = loadOwned(merchantId, policyId);
        RoutePolicyStatus beforeStatus = policy.getStatus();
        String beforeState = auditState(policy);
        policy.setStatus(RoutePolicyStatus.ARCHIVED);
        RoutePolicy saved = policyRepository.save(policy);
        audit(saved, "ARCHIVE", beforeStatus, saved.getStatus(), beforeState, auditState(saved));
        return response(saved, List.of());
    }

    @Transactional(readOnly = true)
    public RoutePolicyResponse get(UUID merchantId, UUID policyId) {
        return validateAndRespond(loadOwned(merchantId, policyId));
    }

    @Transactional(readOnly = true)
    public List<RoutePolicyAuditLogResponse> auditLogs(UUID merchantId, UUID policyId) {
        loadOwned(merchantId, policyId);
        return auditLogRepository.findAllByMerchantIdAndPolicyIdOrderByCreatedAtDesc(merchantId, policyId)
                .stream()
                .map(RoutePolicyAuditLogResponse::from)
                .toList();
    }

    private void replaceRoutes(UUID merchantId, RoutePolicy policy, List<RoutePolicyUpsertRequest.RouteRequest> routes) {
        for (RoutePolicyUpsertRequest.RouteRequest routeRequest : routes) {
            validateJson(routeRequest.conditionsJson(), "conditionsJson");
            RoutePolicyRoute route = new RoutePolicyRoute();
            route.setMerchantId(merchantId);
            route.setPolicyId(policy.getId());
            route.setName(routeRequest.name());
            route.setRouteOrder(routeRequest.routeOrder());
            route.setDefaultRoute(routeRequest.defaultRoute());
            route.setConditionsJson(routeRequest.conditionsJson());
            RoutePolicyRoute savedRoute = routeRepository.save(route);

            for (RoutePolicyUpsertRequest.StepRequest stepRequest : routeRequest.steps()) {
                validateJson(stepRequest.outcomeActionsJson(), "outcomeActionsJson");
                RoutePolicyStep step = new RoutePolicyStep();
                step.setMerchantId(merchantId);
                step.setPolicyId(policy.getId());
                step.setRouteId(savedRoute.getId());
                step.setStepOrder(stepRequest.stepOrder());
                step.setProviderAccountId(stepRequest.providerAccountId());
                step.setTrafficWeight(stepRequest.trafficWeight() > 0 ? stepRequest.trafficWeight() : 100);
                step.setMaxCostBps(stepRequest.maxCostBps());
                step.setSkipIfDegraded(stepRequest.skipIfDegraded() == null || stepRequest.skipIfDegraded());
                step.setOutcomeActionsJson(stepRequest.outcomeActionsJson());
                stepRepository.save(step);
            }
        }
    }

    private RoutePolicyResponse validateAndRespond(RoutePolicy policy) {
        return response(policy, validate(policy));
    }

    private List<RoutePolicyValidationIssue> validate(RoutePolicy policy) {
        return validationService.validate(
                policy,
                routeRepository.findAllByMerchantIdAndPolicyIdOrderByRouteOrderAsc(policy.getMerchantId(), policy.getId()),
                stepRepository.findAllByMerchantIdAndPolicyIdOrderByRouteIdAscStepOrderAsc(policy.getMerchantId(), policy.getId()),
                activeProviderAccountIds(policy.getMerchantId(), policy.getMode()),
                routingAttributeRepository.findAllByMerchantIdAndEnabledTrueOrderByKeyAsc(policy.getMerchantId()));
    }

    private RoutePolicyResponse response(RoutePolicy policy, List<RoutePolicyValidationIssue> issues) {
        return RoutePolicyResponse.from(
                policy,
                routeRepository.findAllByMerchantIdAndPolicyIdOrderByRouteOrderAsc(policy.getMerchantId(), policy.getId()),
                stepRepository.findAllByMerchantIdAndPolicyIdOrderByRouteIdAscStepOrderAsc(policy.getMerchantId(), policy.getId()),
                issues);
    }

    private Set<UUID> activeProviderAccountIds(UUID merchantId, ApiKeyMode mode) {
        return providerAccountRepository.findAllByMerchantIdAndModeOrderByCreatedAtDesc(merchantId, mode)
                .stream()
                .filter(account -> account.getStatus() == ProviderAccountStatus.ACTIVE)
                .map(account -> account.getId())
                .collect(Collectors.toSet());
    }

    private RoutePolicy loadOwned(UUID merchantId, UUID policyId) {
        return policyRepository.findByIdAndMerchantId(policyId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException("Route policy not found"));
    }

    private int nextVersion(UUID merchantId, ApiKeyMode mode) {
        return policyRepository.findAllByMerchantIdAndModeOrderByUpdatedAtDesc(merchantId, mode)
                .stream()
                .mapToInt(RoutePolicy::getPolicyVersion)
                .max()
                .orElse(0) + 1;
    }

    private void validateJson(String raw, String field) {
        try {
            objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be valid JSON");
        }
    }

    private void audit(RoutePolicy policy,
                       String action,
                       RoutePolicyStatus beforeStatus,
                       RoutePolicyStatus afterStatus,
                       String beforeState,
                       String afterState) {
        RoutePolicyAuditLog log = new RoutePolicyAuditLog();
        log.setMerchantId(policy.getMerchantId());
        log.setPolicyId(policy.getId());
        log.setAction(action);
        log.setBeforeStatus(beforeStatus != null ? beforeStatus.name() : null);
        log.setAfterStatus(afterStatus != null ? afterStatus.name() : null);
        log.setBeforeState(beforeState);
        log.setAfterState(afterState);
        auditLogRepository.save(log);
    }

    private String auditState(RoutePolicy policy) {
        try {
            return objectMapper.writeValueAsString(new AuditPolicyState(
                    policy.getId(),
                    policy.getName(),
                    policy.getMode() != null ? policy.getMode().name() : null,
                    policy.getStatus() != null ? policy.getStatus().name() : null,
                    policy.getPolicyVersion(),
                    policy.getPublishedAt()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private record AuditPolicyState(UUID policyId,
                                    String name,
                                    String mode,
                                    String status,
                                    int policyVersion,
                                    Instant publishedAt) {
    }
}
