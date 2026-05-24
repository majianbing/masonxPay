package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.routing.RoutePolicy;
import com.masonx.paygateway.domain.routing.RoutePolicyRoute;
import com.masonx.paygateway.domain.routing.RoutePolicyStep;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePolicyValidationServiceTest {

    private final RoutePolicyValidationService service = new RoutePolicyValidationService();

    @Test
    void acceptsValidDraftPolicyShape() {
        UUID merchantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();

        RoutePolicy policy = policy(merchantId, policyId);
        RoutePolicyRoute route = route(merchantId, policyId, routeId, true);
        RoutePolicyStep step = step(merchantId, policyId, routeId, accountId);

        List<RoutePolicyValidationIssue> issues = service.validate(
                policy, List.of(route), List.of(step), Set.of(accountId));

        assertThat(issues).isEmpty();
    }

    @Test
    void requiresExactlyOneDefaultRoute() {
        UUID merchantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();

        List<RoutePolicyValidationIssue> issues = service.validate(
                policy(merchantId, policyId),
                List.of(route(merchantId, policyId, UUID.randomUUID(), false)),
                List.of(),
                Set.of());

        assertThat(issues).extracting(RoutePolicyValidationIssue::code)
                .contains("routes.default_count");
    }

    @Test
    void rejectsInactiveProviderAccountStep() {
        UUID merchantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();

        List<RoutePolicyValidationIssue> issues = service.validate(
                policy(merchantId, policyId),
                List.of(route(merchantId, policyId, routeId, true)),
                List.of(step(merchantId, policyId, routeId, UUID.randomUUID())),
                Set.of());

        assertThat(issues).extracting(RoutePolicyValidationIssue::code)
                .contains("step.account_inactive");
    }

    private RoutePolicy policy(UUID merchantId, UUID policyId) {
        RoutePolicy policy = new RoutePolicy();
        ReflectionTestUtils.setField(policy, "id", policyId);
        policy.setMerchantId(merchantId);
        policy.setMode(ApiKeyMode.TEST);
        policy.setName("Test policy");
        policy.setPolicyVersion(1);
        return policy;
    }

    private RoutePolicyRoute route(UUID merchantId, UUID policyId, UUID routeId, boolean defaultRoute) {
        RoutePolicyRoute route = new RoutePolicyRoute();
        ReflectionTestUtils.setField(route, "id", routeId);
        route.setMerchantId(merchantId);
        route.setPolicyId(policyId);
        route.setRouteOrder(1);
        route.setName("Default route");
        route.setDefaultRoute(defaultRoute);
        route.setConditionsJson("{}");
        return route;
    }

    private RoutePolicyStep step(UUID merchantId, UUID policyId, UUID routeId, UUID accountId) {
        RoutePolicyStep step = new RoutePolicyStep();
        step.setMerchantId(merchantId);
        step.setPolicyId(policyId);
        step.setRouteId(routeId);
        step.setStepOrder(1);
        step.setProviderAccountId(accountId);
        step.setTrafficWeight(100);
        step.setOutcomeActionsJson("{\"APPROVED\":\"finish\",\"PROVIDER_TIMEOUT\":\"next\"}");
        return step;
    }
}
