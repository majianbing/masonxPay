package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.routing.RoutePolicy;
import com.masonx.paygateway.domain.routing.RoutePolicyRoute;
import com.masonx.paygateway.domain.routing.RoutePolicyStep;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoutePolicyValidationService {

    public List<RoutePolicyValidationIssue> validate(RoutePolicy policy,
                                                     List<RoutePolicyRoute> routes,
                                                     List<RoutePolicyStep> steps,
                                                     Set<UUID> activeProviderAccountIds) {
        List<RoutePolicyValidationIssue> issues = new ArrayList<>();
        if (policy == null) {
            issues.add(issue("policy.missing", "Route policy is required"));
            return issues;
        }

        List<RoutePolicyRoute> safeRoutes = routes != null ? routes : List.of();
        List<RoutePolicyStep> safeSteps = steps != null ? steps : List.of();
        Set<UUID> safeActiveAccounts = activeProviderAccountIds != null ? activeProviderAccountIds : Set.of();

        validatePolicy(policy, issues);
        validateRoutes(policy, safeRoutes, issues);
        validateSteps(policy, safeRoutes, safeSteps, safeActiveAccounts, issues);
        return issues;
    }

    private void validatePolicy(RoutePolicy policy, List<RoutePolicyValidationIssue> issues) {
        if (policy.getMerchantId() == null) {
            issues.add(issue("policy.merchant_missing", "Route policy must include merchant_id"));
        }
        if (policy.getMode() == null) {
            issues.add(issue("policy.mode_missing", "Route policy must include mode"));
        }
        if (policy.getName() == null || policy.getName().isBlank()) {
            issues.add(issue("policy.name_missing", "Route policy must include a name"));
        }
        if (policy.getPolicyVersion() <= 0) {
            issues.add(issue("policy.version_invalid", "Route policy version must be positive"));
        }
    }

    private void validateRoutes(RoutePolicy policy, List<RoutePolicyRoute> routes,
                                List<RoutePolicyValidationIssue> issues) {
        if (routes.isEmpty()) {
            issues.add(issue("routes.empty", "Route policy must include at least one route"));
            return;
        }

        long defaultCount = routes.stream().filter(RoutePolicyRoute::isDefaultRoute).count();
        if (defaultCount != 1) {
            issues.add(issue("routes.default_count", "Route policy must include exactly one default route"));
        }

        Set<Integer> routeOrders = new HashSet<>();
        for (RoutePolicyRoute route : routes) {
            if (!Objects.equals(route.getMerchantId(), policy.getMerchantId())) {
                issues.add(issue("route.merchant_mismatch", "Route merchant_id must match policy merchant_id"));
            }
            if (policy.getId() != null && !Objects.equals(route.getPolicyId(), policy.getId())) {
                issues.add(issue("route.policy_mismatch", "Route policy_id must match policy id"));
            }
            if (!routeOrders.add(route.getRouteOrder())) {
                issues.add(issue("route.order_duplicate", "Route order values must be unique within a policy"));
            }
            if (route.getName() == null || route.getName().isBlank()) {
                issues.add(issue("route.name_missing", "Each route must include a name"));
            }
            if (route.getConditionsJson() == null || route.getConditionsJson().isBlank()) {
                issues.add(issue("route.conditions_missing", "Route conditions_json must not be blank"));
            }
        }
    }

    private void validateSteps(RoutePolicy policy, List<RoutePolicyRoute> routes, List<RoutePolicyStep> steps,
                               Set<UUID> activeProviderAccountIds, List<RoutePolicyValidationIssue> issues) {
        Set<UUID> routeIds = routes.stream()
                .map(RoutePolicyRoute::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, Long> stepCountByRoute = steps.stream()
                .filter(step -> step.getRouteId() != null)
                .collect(Collectors.groupingBy(RoutePolicyStep::getRouteId, Collectors.counting()));

        for (RoutePolicyRoute route : routes) {
            if (route.getId() != null && stepCountByRoute.getOrDefault(route.getId(), 0L) == 0) {
                issues.add(issue("route.steps_empty", "Each route must include at least one route step"));
            }
        }

        Set<String> stepOrders = new HashSet<>();
        for (RoutePolicyStep step : steps) {
            if (!Objects.equals(step.getMerchantId(), policy.getMerchantId())) {
                issues.add(issue("step.merchant_mismatch", "Step merchant_id must match policy merchant_id"));
            }
            if (policy.getId() != null && !Objects.equals(step.getPolicyId(), policy.getId())) {
                issues.add(issue("step.policy_mismatch", "Step policy_id must match policy id"));
            }
            if (step.getRouteId() == null || !routeIds.contains(step.getRouteId())) {
                issues.add(issue("step.route_missing", "Step must reference a route in the same policy"));
            }
            String orderKey = step.getRouteId() + ":" + step.getStepOrder();
            if (!stepOrders.add(orderKey)) {
                issues.add(issue("step.order_duplicate", "Step order values must be unique within a route"));
            }
            if (step.getProviderAccountId() == null || !activeProviderAccountIds.contains(step.getProviderAccountId())) {
                issues.add(issue("step.account_inactive", "Step must target an active provider account for this merchant"));
            }
            if (step.getTrafficWeight() <= 0 || step.getTrafficWeight() > 100) {
                issues.add(issue("step.weight_invalid", "Step traffic_weight must be between 1 and 100"));
            }
            if (step.getOutcomeActionsJson() == null || step.getOutcomeActionsJson().isBlank()) {
                issues.add(issue("step.outcomes_missing", "Step outcome_actions_json must not be blank"));
            }
        }
    }

    private RoutePolicyValidationIssue issue(String code, String message) {
        return new RoutePolicyValidationIssue(code, message);
    }
}
