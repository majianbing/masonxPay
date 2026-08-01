package com.masonx.feeengine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DefaultFeeEngine implements FeeEngine {

    private static final BigDecimal BPS_DENOMINATOR = new BigDecimal("10000");

    private final FeeExpressionEvaluator expressionEvaluator;
    private final FeeRuleValidator validator;

    public DefaultFeeEngine(FeeExpressionEvaluator expressionEvaluator) {
        this.expressionEvaluator = expressionEvaluator;
        this.validator = new FeeRuleValidator(expressionEvaluator);
    }

    public static DefaultFeeEngine withAviator() {
        return new DefaultFeeEngine(new AviatorFeeExpressionEvaluator());
    }

    @Override
    public FeeAssessment assess(FeeScheduleVersion schedule, FeeContextSchema schema, FeeContext context) {
        validator.validate(schedule, schema);
        validateContext(schema, context);

        List<FeeRuleMatch> matches = new ArrayList<>();
        List<FeeLine> lines = new ArrayList<>();
        List<FeeRule> orderedRules = schedule.rules().stream()
                .sorted(Comparator.comparingInt(FeeRule::priority).thenComparing(FeeRule::ruleId))
                .toList();

        for (FeeRule rule : orderedRules) {
            if (!expressionEvaluator.matches(rule.matchExpression(), context)) {
                continue;
            }
            matches.add(new FeeRuleMatch(rule.ruleId(), rule.ruleVersion(), rule.name()));
            for (FeeComponent component : rule.components()) {
                lines.add(calculateLine(schedule, rule, component, context));
            }
            if (rule.stopProcessing()) {
                break;
            }
        }
        return FeeAssessment.of(schedule.scheduleId(), schedule.version(), matches, lines);
    }

    private static void validateContext(FeeContextSchema schema, FeeContext context) {
        for (String fieldName : context.values().keySet()) {
            if (!schema.contains(fieldName)) {
                throw new FeeRuleValidationException("Unknown fee context value: " + fieldName);
            }
        }
        for (FeeContextField field : schema.fields().values()) {
            if (field.required() && !context.values().containsKey(field.name())) {
                throw new FeeRuleValidationException("Missing required fee context field: " + field.name());
            }
            if (!context.values().containsKey(field.name())) {
                continue;
            }
            Object value = context.values().get(field.name());
            if (!matchesType(value, field.type())) {
                throw new FeeRuleValidationException("Fee context field has invalid type: " + field.name());
            }
        }
    }

    private static boolean matchesType(Object value, FeeFieldType type) {
        return switch (type) {
            case STRING -> value instanceof String;
            case DECIMAL -> value instanceof BigDecimal || value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    private static FeeLine calculateLine(FeeScheduleVersion schedule,
                                         FeeRule rule,
                                         FeeComponent component,
                                         FeeContext context) {
        BigDecimal basisAmount = null;
        BigDecimal rawAmount;
        if (component.type() == FeeComponentType.FIXED) {
            rawAmount = component.amount();
        } else {
            basisAmount = context.decimal(component.basisField());
            rawAmount = basisAmount
                    .multiply(new BigDecimal(component.rateBps()))
                    .divide(BPS_DENOMINATOR);
        }

        BigDecimal boundedAmount = applyBounds(rawAmount, component);
        BigDecimal roundedAmount = boundedAmount.setScale(schedule.feeScale(), schedule.roundingMode());
        return new FeeLine(
                rule.ruleId(),
                rule.ruleVersion(),
                rule.name(),
                component.componentId(),
                component.name(),
                component.visibility(),
                schedule.feeCurrency(),
                basisAmount,
                rawAmount,
                roundedAmount,
                schedule.roundingMode(),
                schedule.feeScale(),
                component.metadata());
    }

    private static BigDecimal applyBounds(BigDecimal rawAmount, FeeComponent component) {
        BigDecimal result = rawAmount;
        if (component.minAmount() != null && result.compareTo(component.minAmount()) < 0) {
            result = component.minAmount();
        }
        if (component.maxAmount() != null && result.compareTo(component.maxAmount()) > 0) {
            result = component.maxAmount();
        }
        return result;
    }
}
