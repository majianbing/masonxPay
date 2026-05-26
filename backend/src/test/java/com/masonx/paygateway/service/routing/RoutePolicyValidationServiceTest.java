package com.masonx.paygateway.service.routing;

import com.masonx.paygateway.domain.apikey.ApiKeyMode;
import com.masonx.paygateway.domain.routing.RoutePolicy;
import com.masonx.paygateway.domain.routing.RoutePolicyRoute;
import com.masonx.paygateway.domain.routing.RoutePolicyStep;
import com.masonx.paygateway.domain.routing.RoutingAttribute;
import com.masonx.paygateway.domain.routing.RoutingAttributePiiClassification;
import com.masonx.paygateway.domain.routing.RoutingAttributeSource;
import com.masonx.paygateway.domain.routing.RoutingAttributeType;
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

    @Test
    void rejectsUnknownBuiltInConditionField() {
        UUID merchantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        RoutePolicyRoute route = route(merchantId, policyId, routeId, false);
        route.setConditionsJson("{\"field\":\"unknown_field\",\"operator\":\"eq\",\"value\":\"x\"}");

        List<RoutePolicyValidationIssue> issues = service.validate(
                policy(merchantId, policyId),
                List.of(route, route(merchantId, policyId, UUID.randomUUID(), true)),
                List.of(step(merchantId, policyId, routeId, accountId)),
                Set.of(accountId),
                List.of());

        assertThat(issues).extracting(RoutePolicyValidationIssue::code)
                .contains("condition.field_unknown");
    }

    @Test
    void rejectsUnregisteredMetadataConditionField() {
        UUID merchantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        RoutePolicyRoute route = route(merchantId, policyId, routeId, false);
        route.setConditionsJson("{\"field\":\"metadata.vip_tier\",\"operator\":\"eq\",\"value\":\"gold\"}");

        List<RoutePolicyValidationIssue> issues = service.validate(
                policy(merchantId, policyId),
                List.of(route, route(merchantId, policyId, UUID.randomUUID(), true)),
                List.of(step(merchantId, policyId, routeId, accountId)),
                Set.of(accountId),
                List.of());

        assertThat(issues).extracting(RoutePolicyValidationIssue::code)
                .contains("condition.attribute_unregistered");
    }

    @Test
    void acceptsRegisteredMetadataEnumCondition() {
        UUID merchantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        RoutePolicyRoute route = route(merchantId, policyId, routeId, false);
        route.setConditionsJson("{\"field\":\"metadata.vip_tier\",\"operator\":\"in\",\"value\":[\"gold\",\"platinum\"]}");

        List<RoutePolicyValidationIssue> issues = service.validate(
                policy(merchantId, policyId),
                List.of(route, route(merchantId, policyId, UUID.randomUUID(), true)),
                List.of(step(merchantId, policyId, routeId, accountId)),
                Set.of(accountId),
                List.of(attribute(merchantId, "vip_tier", RoutingAttributeType.ENUM, "eq,in", "gold,platinum")));

        assertThat(issues).extracting(RoutePolicyValidationIssue::code)
                .doesNotContain("condition.attribute_unregistered", "condition.operator_unsupported", "condition.value_enum_invalid");
    }

    @Test
    void rejectsUnsupportedOperatorForBuiltInField() {
        UUID merchantId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        RoutePolicyRoute route = route(merchantId, policyId, routeId, false);
        route.setConditionsJson("{\"field\":\"currency\",\"operator\":\"gt\",\"value\":\"USD\"}");

        List<RoutePolicyValidationIssue> issues = service.validate(
                policy(merchantId, policyId),
                List.of(route, route(merchantId, policyId, UUID.randomUUID(), true)),
                List.of(step(merchantId, policyId, routeId, accountId)),
                Set.of(accountId),
                List.of());

        assertThat(issues).extracting(RoutePolicyValidationIssue::code)
                .contains("condition.operator_unsupported");
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

    private RoutingAttribute attribute(UUID merchantId,
                                       String key,
                                       RoutingAttributeType type,
                                       String operators,
                                       String enumValues) {
        RoutingAttribute attribute = new RoutingAttribute();
        attribute.setMerchantId(merchantId);
        attribute.setKey(key);
        attribute.setLabel(key);
        attribute.setType(type);
        attribute.setSource(RoutingAttributeSource.PAYMENT_METADATA);
        attribute.setAllowedOperators(operators);
        attribute.setEnumValues(enumValues);
        attribute.setPiiClassification(RoutingAttributePiiClassification.NONE);
        attribute.setEnabled(true);
        return attribute;
    }
}
