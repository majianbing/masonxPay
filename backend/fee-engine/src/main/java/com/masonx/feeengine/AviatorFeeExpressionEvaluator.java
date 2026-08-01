package com.masonx.feeengine;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Feature;
import com.googlecode.aviator.Options;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class AviatorFeeExpressionEvaluator implements FeeExpressionEvaluator {

    private final AviatorEvaluatorInstance evaluator;

    public AviatorFeeExpressionEvaluator() {
        this.evaluator = AviatorEvaluator.newInstance();
        this.evaluator.enableSandboxMode();
        this.evaluator.setOption(Options.ALLOWED_CLASS_SET, Collections.emptySet());
        this.evaluator.setOption(Options.ASSIGNABLE_ALLOWED_CLASS_SET, Collections.emptySet());
        this.evaluator.setOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR, false);
        this.evaluator.setOption(Options.USE_USER_ENV_AS_TOP_ENV_DIRECTLY, false);
        this.evaluator.setOption(Options.MAX_LOOP_COUNT, 0);
        this.evaluator.setOption(Options.EVAL_TIMEOUT_MS, 100);
        this.evaluator.disableFeature(Feature.Assignment);
        this.evaluator.disableFeature(Feature.Return);
        this.evaluator.disableFeature(Feature.ForLoop);
        this.evaluator.disableFeature(Feature.WhileLoop);
        this.evaluator.disableFeature(Feature.Let);
        this.evaluator.disableFeature(Feature.LexicalScope);
        this.evaluator.disableFeature(Feature.Lambda);
        this.evaluator.disableFeature(Feature.Fn);
        this.evaluator.disableFeature(Feature.InternalVars);
        this.evaluator.disableFeature(Feature.Module);
        this.evaluator.disableFeature(Feature.ExceptionHandle);
        this.evaluator.disableFeature(Feature.NewInstance);
        this.evaluator.disableFeature(Feature.StringInterpolation);
        this.evaluator.disableFeature(Feature.Use);
        this.evaluator.disableFeature(Feature.StaticFields);
        this.evaluator.disableFeature(Feature.StaticMethods);
    }

    @Override
    public boolean matches(String expression, FeeContext context) {
        try {
            Object result = evaluator.execute(expression, context.values(), false);
            if (result instanceof Boolean bool) {
                return bool;
            }
            throw new FeeRuleValidationException("Fee expression must return boolean: " + expression);
        } catch (FeeRuleValidationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new FeeRuleValidationException("Fee expression evaluation failed: " + expression, ex);
        }
    }

    @Override
    public Set<String> referencedFields(String expression) {
        try {
            Expression compiled = evaluator.compile(expression, false);
            return new LinkedHashSet<>(compiled.getVariableFullNames());
        } catch (RuntimeException ex) {
            throw new FeeRuleValidationException("Fee expression compile failed: " + expression, ex);
        }
    }

    @Override
    public void validateSyntax(String expression) {
        try {
            Expression compiled = evaluator.compile(expression, false);
            if (!compiled.getFunctionNames().isEmpty()) {
                throw new FeeRuleValidationException("Fee expression functions are not allowed: " + expression);
            }
        } catch (RuntimeException ex) {
            if (ex instanceof FeeRuleValidationException validationException) {
                throw validationException;
            }
            throw new FeeRuleValidationException("Invalid fee expression: " + expression, ex);
        }
    }
}
