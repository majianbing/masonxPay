package com.masonx.feeengine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultFeeEngineTest {

    private final FeeEngine engine = DefaultFeeEngine.withAviator();

    @Test
    void assess_applies_small_amount_fixed_fee() {
        FeeScheduleVersion schedule = schedule(List.of(smallAmountRule()));
        FeeContext context = context(Map.of(
                "eventType", "CARD_CLEARING",
                "amountUsd", new BigDecimal("9.99"),
                "amount", new BigDecimal("9.99"),
                "transactionCurrency", "USD",
                "cardCurrency", "USD"));

        FeeAssessment assessment = engine.assess(schedule, schema(), context);

        assertThat(assessment.matchedRules()).extracting(FeeRuleMatch::ruleId)
                .containsExactly("rule_small_amount");
        assertThat(assessment.lines()).hasSize(1);
        FeeLine line = assessment.lines().get(0);
        assertThat(line.amount()).isEqualByComparingTo("0.50");
        assertThat(line.rawCalculatedAmount()).isEqualByComparingTo("0.50");
        assertThat(line.roundingMode()).isEqualTo(RoundingMode.HALF_UP);
        assertThat(line.roundingScale()).isEqualTo(2);
        assertThat(assessment.visibleTotals()).containsEntry("USD", new BigDecimal("0.50"));
        assertThat(assessment.hiddenTotals()).isEmpty();
    }

    @Test
    void assess_applies_fx_percentage_and_fixed_hidden_fee_with_rounding() {
        FeeScheduleVersion schedule = schedule(List.of(fxAuthRule()));
        FeeContext context = context(Map.of(
                "eventType", "CARD_AUTH",
                "amountUsd", new BigDecimal("20.00"),
                "amount", new BigDecimal("11.00"),
                "transactionCurrency", "EUR",
                "cardCurrency", "USD"));

        FeeAssessment assessment = engine.assess(schedule, schema(), context);

        assertThat(assessment.matchedRules()).extracting(FeeRuleMatch::ruleId)
                .containsExactly("rule_fx_auth");
        assertThat(assessment.lines()).hasSize(2);
        FeeLine percent = assessment.lines().get(0);
        assertThat(percent.basisAmount()).isEqualByComparingTo("11.00");
        assertThat(percent.rawCalculatedAmount()).isEqualByComparingTo("0.055");
        assertThat(percent.amount()).isEqualByComparingTo("0.06");
        assertThat(assessment.hiddenTotals()).containsEntry("USD", new BigDecimal("0.16"));
    }

    @Test
    void assess_orders_rules_by_priority_then_rule_id_and_honors_stop_processing() {
        FeeRule highPriorityStop = new FeeRule(
                "rule_a",
                1,
                "high_priority_stop",
                10,
                "amountUsd < 10",
                List.of(FeeComponent.fixed("fixed_a", "fixed_a", new BigDecimal("0.25"), "USD",
                        FeeVisibility.MERCHANT_VISIBLE)),
                FeeVisibility.MERCHANT_VISIBLE,
                true,
                Map.of());
        FeeScheduleVersion schedule = schedule(List.of(smallAmountRule(), highPriorityStop));
        FeeContext context = context(Map.of(
                "eventType", "CARD_CLEARING",
                "amountUsd", new BigDecimal("5.00"),
                "amount", new BigDecimal("5.00"),
                "transactionCurrency", "USD",
                "cardCurrency", "USD"));

        FeeAssessment assessment = engine.assess(schedule, schema(), context);

        assertThat(assessment.matchedRules()).extracting(FeeRuleMatch::ruleId)
                .containsExactly("rule_a");
        assertThat(assessment.visibleTotals()).containsEntry("USD", new BigDecimal("0.25"));
    }

    @Test
    void assess_rejects_unknown_expression_field_before_activation() {
        FeeRule typoRule = new FeeRule(
                "rule_typo",
                1,
                "typo_rule",
                1,
                "amuntUsd < 10",
                List.of(FeeComponent.fixed("fixed_typo", "fixed_typo", new BigDecimal("0.50"), "USD",
                        FeeVisibility.MERCHANT_VISIBLE)),
                FeeVisibility.MERCHANT_VISIBLE,
                false,
                Map.of());

        assertThatThrownBy(() -> engine.assess(schedule(List.of(typoRule)), schema(), context(Map.of(
                "eventType", "CARD_CLEARING",
                "amountUsd", new BigDecimal("5.00"),
                "amount", new BigDecimal("5.00"),
                "transactionCurrency", "USD",
                "cardCurrency", "USD"))))
                .isInstanceOf(FeeRuleValidationException.class)
                .hasMessageContaining("Unknown fee context field: amuntUsd");
    }

    @Test
    void assess_rejects_fixed_component_currency_that_differs_from_fee_currency() {
        FeeRule eurFixedRule = new FeeRule(
                "rule_eur",
                1,
                "eur_fixed",
                1,
                "amountUsd < 10",
                List.of(FeeComponent.fixed("fixed_eur", "fixed_eur", new BigDecimal("0.50"), "EUR",
                        FeeVisibility.MERCHANT_VISIBLE)),
                FeeVisibility.MERCHANT_VISIBLE,
                false,
                Map.of());

        assertThatThrownBy(() -> engine.assess(schedule(List.of(eurFixedRule)), schema(), context(Map.of(
                "eventType", "CARD_CLEARING",
                "amountUsd", new BigDecimal("5.00"),
                "amount", new BigDecimal("5.00"),
                "transactionCurrency", "USD",
                "cardCurrency", "USD"))))
                .isInstanceOf(FeeRuleValidationException.class)
                .hasMessageContaining("currency must match fee currency");
    }

    @Test
    void assess_rejects_expression_functions_before_activation() {
        FeeRule functionRule = new FeeRule(
                "rule_function",
                1,
                "function_rule",
                1,
                "string.startsWith(cardCurrency, 'U')",
                List.of(FeeComponent.fixed("fixed_function", "fixed_function", new BigDecimal("0.50"), "USD",
                        FeeVisibility.MERCHANT_VISIBLE)),
                FeeVisibility.MERCHANT_VISIBLE,
                false,
                Map.of());

        assertThatThrownBy(() -> engine.assess(schedule(List.of(functionRule)), schema(), context(Map.of(
                "eventType", "CARD_CLEARING",
                "amountUsd", new BigDecimal("5.00"),
                "amount", new BigDecimal("5.00"),
                "transactionCurrency", "USD",
                "cardCurrency", "USD"))))
                .isInstanceOf(FeeRuleValidationException.class)
                .hasMessageContaining("functions are not allowed");
    }

    @Test
    void assess_rejects_method_invocation_syntax_before_activation() {
        FeeRule methodRule = new FeeRule(
                "rule_method",
                1,
                "method_rule",
                1,
                "cardCurrency.startsWith('U')",
                List.of(FeeComponent.fixed("fixed_method", "fixed_method", new BigDecimal("0.50"), "USD",
                        FeeVisibility.MERCHANT_VISIBLE)),
                FeeVisibility.MERCHANT_VISIBLE,
                false,
                Map.of());

        assertThatThrownBy(() -> engine.assess(schedule(List.of(methodRule)), schema(), context(Map.of(
                "eventType", "CARD_CLEARING",
                "amountUsd", new BigDecimal("5.00"),
                "amount", new BigDecimal("5.00"),
                "transactionCurrency", "USD",
                "cardCurrency", "USD"))))
                .isInstanceOf(FeeRuleValidationException.class);
    }

    @Test
    void assess_rejects_unknown_context_value() {
        assertThatThrownBy(() -> engine.assess(schedule(List.of(smallAmountRule())), schema(), context(Map.of(
                "eventType", "CARD_CLEARING",
                "amountUsd", new BigDecimal("5.00"),
                "amount", new BigDecimal("5.00"),
                "transactionCurrency", "USD",
                "cardCurrency", "USD",
                "rawPan", "4111111111111111"))))
                .isInstanceOf(FeeRuleValidationException.class)
                .hasMessageContaining("Unknown fee context value: rawPan");
    }

    private static FeeRule smallAmountRule() {
        return new FeeRule(
                "rule_small_amount",
                1,
                "small_amount_surcharge",
                100,
                "amountUsd < 10",
                List.of(FeeComponent.fixed("fixed_small_amount", "small_amount_fixed",
                        new BigDecimal("0.50"), "USD", FeeVisibility.MERCHANT_VISIBLE)),
                FeeVisibility.MERCHANT_VISIBLE,
                false,
                Map.of());
    }

    private static FeeRule fxAuthRule() {
        return new FeeRule(
                "rule_fx_auth",
                3,
                "fx_purchase_auth_fee",
                200,
                "eventType == 'CARD_AUTH' && transactionCurrency != cardCurrency",
                List.of(
                        FeeComponent.percentage("fx_percent", "fx_markup_percent", 50,
                                "amount", FeeVisibility.PLATFORM_HIDDEN),
                        FeeComponent.fixed("fx_fixed", "fx_fixed", new BigDecimal("0.10"), "USD",
                                FeeVisibility.PLATFORM_HIDDEN)),
                FeeVisibility.PLATFORM_HIDDEN,
                false,
                Map.of());
    }

    private static FeeScheduleVersion schedule(List<FeeRule> rules) {
        return new FeeScheduleVersion("fs_1", 1, "USD", 2, RoundingMode.HALF_UP, rules, Map.of());
    }

    private static FeeContext context(Map<String, Object> values) {
        return FeeContext.of(values);
    }

    private static FeeContextSchema schema() {
        return FeeContextSchema.of(List.of(
                new FeeContextField("eventType", FeeFieldType.STRING, true),
                new FeeContextField("amountUsd", FeeFieldType.DECIMAL, true),
                new FeeContextField("amount", FeeFieldType.DECIMAL, true),
                new FeeContextField("transactionCurrency", FeeFieldType.STRING, true),
                new FeeContextField("cardCurrency", FeeFieldType.STRING, true)));
    }
}
