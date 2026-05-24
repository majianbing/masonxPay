package com.masonx.paygateway.web.dto;

import com.masonx.paygateway.domain.routing.RoutePolicy;
import com.masonx.paygateway.domain.routing.RoutePolicyRoute;
import com.masonx.paygateway.domain.routing.RoutePolicyStep;
import com.masonx.paygateway.service.routing.RoutePolicyValidationIssue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record RoutePolicyResponse(
        UUID id,
        UUID merchantId,
        String mode,
        String name,
        String status,
        int policyVersion,
        String description,
        Instant publishedAt,
        List<Route> routes,
        List<RoutePolicyValidationIssue> validationIssues
) {
    public static RoutePolicyResponse from(RoutePolicy policy,
                                           List<RoutePolicyRoute> routes,
                                           List<RoutePolicyStep> steps,
                                           List<RoutePolicyValidationIssue> validationIssues) {
        Map<UUID, List<RoutePolicyStep>> stepsByRoute = steps.stream()
                .collect(Collectors.groupingBy(RoutePolicyStep::getRouteId));
        return new RoutePolicyResponse(
                policy.getId(),
                policy.getMerchantId(),
                policy.getMode().name(),
                policy.getName(),
                policy.getStatus().name(),
                policy.getPolicyVersion(),
                policy.getDescription(),
                policy.getPublishedAt(),
                routes.stream()
                        .map(route -> Route.from(route, stepsByRoute.getOrDefault(route.getId(), List.of())))
                        .toList(),
                validationIssues != null ? validationIssues : List.of()
        );
    }

    public record Route(
            UUID id,
            int routeOrder,
            String name,
            boolean defaultRoute,
            String conditionsJson,
            List<Step> steps
    ) {
        static Route from(RoutePolicyRoute route, List<RoutePolicyStep> steps) {
            return new Route(
                    route.getId(),
                    route.getRouteOrder(),
                    route.getName(),
                    route.isDefaultRoute(),
                    route.getConditionsJson(),
                    steps.stream().map(Step::from).toList()
            );
        }
    }

    public record Step(
            UUID id,
            int stepOrder,
            UUID providerAccountId,
            int trafficWeight,
            Integer maxCostBps,
            boolean skipIfDegraded,
            String outcomeActionsJson
    ) {
        static Step from(RoutePolicyStep step) {
            return new Step(
                    step.getId(),
                    step.getStepOrder(),
                    step.getProviderAccountId(),
                    step.getTrafficWeight(),
                    step.getMaxCostBps(),
                    step.isSkipIfDegraded(),
                    step.getOutcomeActionsJson()
            );
        }
    }
}
