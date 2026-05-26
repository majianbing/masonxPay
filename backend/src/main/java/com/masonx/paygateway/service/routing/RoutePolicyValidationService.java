package com.masonx.paygateway.service.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.masonx.paygateway.domain.routing.RoutePolicy;
import com.masonx.paygateway.domain.routing.RoutePolicyRoute;
import com.masonx.paygateway.domain.routing.RoutePolicyStep;
import com.masonx.paygateway.domain.routing.RoutingAttribute;
import com.masonx.paygateway.domain.routing.RoutingAttributeType;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RoutePolicyValidationService {

    private static final Set<String> TEXT_OPERATORS = Set.of("eq", "not_eq", "ne", "in", "missing");
    private static final Set<String> NUMBER_OPERATORS = Set.of("eq", "not_eq", "ne", "gt", "gte", "lt", "lte", "between", "missing");
    private static final Set<String> BOOLEAN_OPERATORS = Set.of("eq", "not_eq", "ne", "in", "missing");
    private static final Map<String, RoutingAttributeType> BUILT_IN_FIELDS = Map.ofEntries(
            Map.entry("amount", RoutingAttributeType.NUMBER),
            Map.entry("currency", RoutingAttributeType.CURRENCY),
            Map.entry("country", RoutingAttributeType.COUNTRY),
            Map.entry("payment_method_type", RoutingAttributeType.STRING),
            Map.entry("payment_method", RoutingAttributeType.STRING),
            Map.entry("capture_method", RoutingAttributeType.STRING),
            Map.entry("customer_id", RoutingAttributeType.STRING),
            Map.entry("order_id", RoutingAttributeType.STRING),
            Map.entry("instrument_source", RoutingAttributeType.STRING),
            Map.entry("instrument_portability", RoutingAttributeType.STRING),
            Map.entry("card_brand", RoutingAttributeType.STRING),
            Map.entry("bin_country", RoutingAttributeType.COUNTRY),
            Map.entry("issuer_country", RoutingAttributeType.COUNTRY),
            Map.entry("card_type", RoutingAttributeType.STRING),
            Map.entry("wallet_type", RoutingAttributeType.STRING));

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<RoutePolicyValidationIssue> validate(RoutePolicy policy,
                                                     List<RoutePolicyRoute> routes,
                                                     List<RoutePolicyStep> steps,
                                                     Set<UUID> activeProviderAccountIds) {
        return validate(policy, routes, steps, activeProviderAccountIds, List.of());
    }

    public List<RoutePolicyValidationIssue> validate(RoutePolicy policy,
                                                     List<RoutePolicyRoute> routes,
                                                     List<RoutePolicyStep> steps,
                                                     Set<UUID> activeProviderAccountIds,
                                                     List<RoutingAttribute> routingAttributes) {
        List<RoutePolicyValidationIssue> issues = new ArrayList<>();
        if (policy == null) {
            issues.add(issue("policy.missing", "Route policy is required"));
            return issues;
        }

        List<RoutePolicyRoute> safeRoutes = routes != null ? routes : List.of();
        List<RoutePolicyStep> safeSteps = steps != null ? steps : List.of();
        Set<UUID> safeActiveAccounts = activeProviderAccountIds != null ? activeProviderAccountIds : Set.of();
        Map<String, RoutingAttribute> customAttributes = (routingAttributes != null ? routingAttributes : List.<RoutingAttribute>of())
                .stream()
                .filter(RoutingAttribute::isEnabled)
                .collect(Collectors.toMap(RoutingAttribute::getKey, attribute -> attribute, (left, right) -> left));

        validatePolicy(policy, issues);
        validateRoutes(policy, safeRoutes, customAttributes, issues);
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
                                Map<String, RoutingAttribute> customAttributes,
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
            } else {
                validateConditions(route, customAttributes, issues);
            }
        }
    }

    private void validateConditions(RoutePolicyRoute route,
                                    Map<String, RoutingAttribute> customAttributes,
                                    List<RoutePolicyValidationIssue> issues) {
        JsonNode root;
        try {
            root = objectMapper.readTree(route.getConditionsJson());
        } catch (Exception e) {
            issues.add(issue("route.conditions_invalid_json", "Route conditions_json must be valid JSON"));
            return;
        }

        if (!root.isObject()) {
            issues.add(issue("route.conditions_invalid_shape", "Route conditions_json must be a JSON object"));
            return;
        }

        if (root.isEmpty()) {
            if (!route.isDefaultRoute()) {
                issues.add(issue("route.conditions_default_only", "Only the default route may use empty conditions"));
            }
            return;
        }

        JsonNode all = root.get("all");
        if (all != null) {
            if (!all.isArray() || all.isEmpty()) {
                issues.add(issue("condition.all_invalid", "Condition all must be a non-empty array"));
                return;
            }
            for (JsonNode condition : all) {
                validateConditionObject(condition, customAttributes, issues);
            }
            return;
        }

        if (root.has("field")) {
            validateConditionObject(root, customAttributes, issues);
            return;
        }

        root.fields().forEachRemaining(entry -> validateSimpleCondition(entry.getKey(), entry.getValue(), customAttributes, issues));
    }

    private void validateConditionObject(JsonNode condition,
                                         Map<String, RoutingAttribute> customAttributes,
                                         List<RoutePolicyValidationIssue> issues) {
        if (condition == null || !condition.isObject()) {
            issues.add(issue("condition.invalid_shape", "Each condition must be a JSON object"));
            return;
        }
        JsonNode fieldNode = condition.get("field");
        if (fieldNode == null || !fieldNode.isTextual() || fieldNode.asText().isBlank()) {
            issues.add(issue("condition.field_missing", "Each condition must include a field"));
            return;
        }
        String operator = condition.has("operator") && condition.get("operator").isTextual()
                ? condition.get("operator").asText("eq").trim().toLowerCase(Locale.ROOT)
                : "eq";
        validateFieldOperatorAndValue(fieldNode.asText().trim(), operator, condition.get("value"), customAttributes, issues);
    }

    private void validateSimpleCondition(String field,
                                         JsonNode value,
                                         Map<String, RoutingAttribute> customAttributes,
                                         List<RoutePolicyValidationIssue> issues) {
        validateFieldOperatorAndValue(field, "eq", value, customAttributes, issues);
    }

    private void validateFieldOperatorAndValue(String field,
                                               String operator,
                                               JsonNode value,
                                               Map<String, RoutingAttribute> customAttributes,
                                               List<RoutePolicyValidationIssue> issues) {
        if (field == null || field.isBlank()) {
            issues.add(issue("condition.field_missing", "Each condition must include a field"));
            return;
        }
        FieldSchema schema = schemaFor(field, customAttributes, issues);
        if (schema == null) {
            return;
        }
        if (!schema.allowedOperators().contains(operator)) {
            issues.add(issue("condition.operator_unsupported", "Condition operator is not supported for field " + field));
            return;
        }
        validateOperatorValue(field, operator, value, schema, issues);
    }

    private FieldSchema schemaFor(String field,
                                  Map<String, RoutingAttribute> customAttributes,
                                  List<RoutePolicyValidationIssue> issues) {
        RoutingAttributeType builtInType = BUILT_IN_FIELDS.get(field);
        if (builtInType != null) {
            return new FieldSchema(builtInType, operatorsFor(builtInType), Set.of(), 255);
        }
        if (!field.startsWith("metadata.")) {
            issues.add(issue("condition.field_unknown", "Condition field is not a registered routing field: " + field));
            return null;
        }
        String key = field.substring("metadata.".length());
        RoutingAttribute attribute = customAttributes.get(key);
        if (attribute == null) {
            issues.add(issue("condition.attribute_unregistered", "Metadata routing attribute is not registered: " + key));
            return null;
        }
        Set<String> operators = parseTextSet(attribute.getAllowedOperators());
        if (operators.isEmpty()) {
            operators = operatorsFor(attribute.getType());
        } else {
            operators = operators.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        }
        return new FieldSchema(
                attribute.getType(),
                operators,
                parseTextSet(attribute.getEnumValues()),
                Math.max(1, attribute.getMaxValueLength()));
    }

    private void validateOperatorValue(String field,
                                       String operator,
                                       JsonNode value,
                                       FieldSchema schema,
                                       List<RoutePolicyValidationIssue> issues) {
        if ("missing".equals(operator)) {
            return;
        }
        if (value == null || value.isNull()) {
            issues.add(issue("condition.value_missing", "Condition value is required for field " + field));
            return;
        }
        if ("in".equals(operator)) {
            if (!value.isArray() || value.isEmpty()) {
                issues.add(issue("condition.value_array_required", "Condition in operator requires a non-empty value array"));
                return;
            }
            value.forEach(item -> validateScalarValue(field, item, schema, issues));
            return;
        }
        if ("between".equals(operator)) {
            if (!value.isArray() || value.size() != 2 || !value.get(0).isNumber() || !value.get(1).isNumber()) {
                issues.add(issue("condition.value_between_required", "Condition between operator requires two numeric values"));
            }
            return;
        }
        validateScalarValue(field, value, schema, issues);
    }

    private void validateScalarValue(String field,
                                     JsonNode value,
                                     FieldSchema schema,
                                     List<RoutePolicyValidationIssue> issues) {
        switch (schema.type()) {
            case NUMBER -> {
                if (!value.isNumber()) {
                    issues.add(issue("condition.value_number_required", "Condition value must be numeric for field " + field));
                }
            }
            case BOOLEAN -> {
                if (!value.isBoolean()) {
                    issues.add(issue("condition.value_boolean_required", "Condition value must be boolean for field " + field));
                }
            }
            case ENUM -> {
                if (!value.isTextual()) {
                    issues.add(issue("condition.value_text_required", "Condition value must be text for field " + field));
                    return;
                }
                if (!schema.enumValues().isEmpty() && !schema.enumValues().contains(value.asText())) {
                    issues.add(issue("condition.value_enum_invalid", "Condition value is not allowed for field " + field));
                }
            }
            case COUNTRY -> validateTextLength(field, value, 2, issues);
            case CURRENCY -> validateTextLength(field, value, 3, issues);
            case STRING, EMAIL_DOMAIN -> {
                if (!value.isTextual()) {
                    issues.add(issue("condition.value_text_required", "Condition value must be text for field " + field));
                    return;
                }
                if (value.asText().length() > schema.maxValueLength()) {
                    issues.add(issue("condition.value_too_long", "Condition value exceeds max length for field " + field));
                }
            }
        }
    }

    private void validateTextLength(String field, JsonNode value, int length, List<RoutePolicyValidationIssue> issues) {
        if (!value.isTextual() || value.asText().length() != length) {
            issues.add(issue("condition.value_format_invalid", "Condition value has an invalid format for field " + field));
        }
    }

    private Set<String> operatorsFor(RoutingAttributeType type) {
        return switch (type) {
            case NUMBER -> NUMBER_OPERATORS;
            case BOOLEAN -> BOOLEAN_OPERATORS;
            case STRING, ENUM, COUNTRY, CURRENCY, EMAIL_DOMAIN -> TEXT_OPERATORS;
        };
    }

    private Set<String> parseTextSet(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("[")) {
                JsonNode node = objectMapper.readTree(trimmed);
                if (node.isArray()) {
                    Set<String> values = new LinkedHashSet<>();
                    node.forEach(item -> {
                        if (item.isTextual() && !item.asText().isBlank()) {
                            values.add(item.asText().trim());
                        }
                    });
                    return values;
                }
            }
        } catch (Exception ignored) {
            // Fall back to comma-separated parsing for legacy/simple inputs.
        }
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
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

    private record FieldSchema(RoutingAttributeType type,
                               Set<String> allowedOperators,
                               Set<String> enumValues,
                               int maxValueLength) {
    }
}
