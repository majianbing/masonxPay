package com.masonx.feeengine;

import java.util.Set;

public class FeeRuleValidator {

    private final FeeExpressionEvaluator expressionEvaluator;

    public FeeRuleValidator(FeeExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
    }

    public void validate(FeeScheduleVersion schedule, FeeContextSchema schema) {
        for (FeeRule rule : schedule.rules()) {
            validateRule(schedule, schema, rule);
        }
    }

    private void validateRule(FeeScheduleVersion schedule, FeeContextSchema schema, FeeRule rule) {
        expressionEvaluator.validateSyntax(rule.matchExpression());
        Set<String> fields = expressionEvaluator.referencedFields(rule.matchExpression());
        for (String field : fields) {
            schema.require(field);
        }
        if (rule.components().isEmpty()) {
            throw new FeeRuleValidationException("Fee rule must contain at least one component: " + rule.ruleId());
        }
        for (FeeComponent component : rule.components()) {
            validateComponent(schedule, schema, rule, component);
        }
    }

    private void validateComponent(FeeScheduleVersion schedule,
                                   FeeContextSchema schema,
                                   FeeRule rule,
                                   FeeComponent component) {
        if (component.type() == FeeComponentType.FIXED) {
            if (component.amount() == null) {
                throw new FeeRuleValidationException("Fixed fee component amount is required: "
                        + component.componentId());
            }
            if (component.amount().signum() < 0) {
                throw new FeeRuleValidationException("Fixed fee component amount must be non-negative: "
                        + component.componentId());
            }
            if (component.currency() == null || component.currency().isBlank()) {
                throw new FeeRuleValidationException("Fixed fee component currency is required: "
                        + component.componentId());
            }
            if (!schedule.feeCurrency().equalsIgnoreCase(component.currency())) {
                throw new FeeRuleValidationException("Fixed fee component currency must match fee currency: "
                        + component.componentId());
            }
        } else if (component.type() == FeeComponentType.PERCENTAGE) {
            if (component.rateBps() == null) {
                throw new FeeRuleValidationException("Percentage fee component rateBps is required: "
                        + component.componentId());
            }
            if (component.rateBps() < 0) {
                throw new FeeRuleValidationException("Percentage fee component rateBps must be non-negative: "
                        + component.componentId());
            }
            if (component.basisField() == null || component.basisField().isBlank()) {
                throw new FeeRuleValidationException("Percentage fee component basisField is required: "
                        + component.componentId());
            }
            FeeContextField field = schema.require(component.basisField());
            if (field.type() != FeeFieldType.DECIMAL) {
                throw new FeeRuleValidationException("Percentage fee basis field must be DECIMAL: "
                        + component.basisField());
            }
        } else {
            throw new FeeRuleValidationException("Unsupported fee component type in rule " + rule.ruleId());
        }
    }
}
