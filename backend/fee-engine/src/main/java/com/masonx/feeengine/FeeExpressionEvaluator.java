package com.masonx.feeengine;

import java.util.Set;

public interface FeeExpressionEvaluator {
    boolean matches(String expression, FeeContext context);

    Set<String> referencedFields(String expression);

    void validateSyntax(String expression);
}
