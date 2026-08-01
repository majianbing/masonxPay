package com.masonx.feeengine;

public record FeeRuleMatch(
        String ruleId,
        int ruleVersion,
        String ruleName
) {
}
