package com.masonx.paygateway.ai.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAuthorityPolicyTest {

    @Test
    void everyActionIsEitherAdvisoryOrForbidden_neverBoth_neverNeither() {
        EnumSet<AiAction> union = EnumSet.noneOf(AiAction.class);
        union.addAll(AiAuthorityPolicy.advisoryActions());
        union.addAll(AiAuthorityPolicy.forbiddenActions());

        assertThat(union).containsExactlyInAnyOrder(AiAction.values());
        assertThat(AiAuthorityPolicy.advisoryActions())
                .doesNotContainAnyElementsOf(AiAuthorityPolicy.forbiddenActions());
    }

    @Test
    void paymentDecisions_areForbidden() {
        assertThat(AiAuthorityPolicy.forbiddenActions()).contains(
                AiAction.AUTHORIZE_PAYMENT,
                AiAction.DECLINE_PAYMENT,
                AiAction.ROUTE_PAYMENT,
                AiAction.CAPTURE_PAYMENT,
                AiAction.REFUND_PAYMENT);
    }

    @Test
    void configurationMutationAndApproval_areForbidden() {
        assertThat(AiAuthorityPolicy.forbiddenActions()).contains(
                AiAction.MUTATE_ROUTING_RULES,
                AiAction.APPLY_CONFIGURATION_CHANGE,
                AiAction.APPROVE_PROPOSAL);
    }

    @Test
    void scopeBypassesAndBroadTools_areForbidden() {
        assertThat(AiAuthorityPolicy.forbiddenActions()).contains(
                AiAction.BYPASS_TENANT_SCOPE,
                AiAction.BYPASS_MODE_SCOPE,
                AiAction.BYPASS_RBAC,
                AiAction.BYPASS_VALIDATOR,
                AiAction.EXECUTE_ARBITRARY_SQL,
                AiAction.EXECUTE_ARBITRARY_HTTP,
                AiAction.ACCESS_FILESYSTEM,
                AiAction.READ_SENSITIVE_DATA);
    }

    @Test
    void analysisAndDrafting_areAdvisory() {
        assertThat(AiAuthorityPolicy.advisoryActions()).containsExactlyInAnyOrder(
                AiAction.ANSWER_DOCUMENTATION_QUESTION,
                AiAction.ANALYZE_TELEMETRY,
                AiAction.EXPLAIN_INCIDENT,
                AiAction.RECOMMEND_ROUTING_POLICY_CHANGE,
                AiAction.DRAFT_CONFIGURATION_CHANGE);
    }

    @ParameterizedTest
    @EnumSource(value = AiAction.class, names = {
            "AUTHORIZE_PAYMENT", "DECLINE_PAYMENT", "ROUTE_PAYMENT", "MUTATE_ROUTING_RULES",
            "APPLY_CONFIGURATION_CHANGE", "APPROVE_PROPOSAL", "BYPASS_TENANT_SCOPE", "READ_SENSITIVE_DATA"})
    void requireAdvisory_forbiddenAction_throwsClientSafeViolation(AiAction action) {
        assertThatThrownBy(() -> AiAuthorityPolicy.requireAdvisory(action))
                .isInstanceOf(AiAuthorityViolationException.class)
                .satisfies(ex -> {
                    AiAuthorityViolationException violation = (AiAuthorityViolationException) ex;
                    assertThat(violation.code()).isEqualTo(AiAuthorityViolationException.CODE);
                    assertThat(violation.httpStatus()).isEqualTo(403);
                    assertThat(violation.action()).isEqualTo(action);
                });
    }

    @ParameterizedTest
    @EnumSource(value = AiAction.class, names = {
            "ANSWER_DOCUMENTATION_QUESTION", "ANALYZE_TELEMETRY", "EXPLAIN_INCIDENT",
            "RECOMMEND_ROUTING_POLICY_CHANGE", "DRAFT_CONFIGURATION_CHANGE"})
    void requireAdvisory_advisoryAction_passes(AiAction action) {
        AiAuthorityPolicy.requireAdvisory(action);
        assertThat(AiAuthorityPolicy.isPermitted(action)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(AiDeploymentMode.class)
    void mayCallModel_neverWidensAuthorityForForbiddenAction(AiDeploymentMode mode) {
        assertThatThrownBy(() -> AiAuthorityPolicy.mayCallModel(mode, AiAction.ROUTE_PAYMENT))
                .isInstanceOf(AiAuthorityViolationException.class);
    }

    @Test
    void mayCallModel_disabledMode_blocksAdvisoryActionWithoutThrowing() {
        assertThat(AiAuthorityPolicy.mayCallModel(AiDeploymentMode.DISABLED, AiAction.EXPLAIN_INCIDENT)).isFalse();
        assertThat(AiAuthorityPolicy.mayCallModel(AiDeploymentMode.EXTERNAL_REDACTED, AiAction.EXPLAIN_INCIDENT)).isTrue();
        assertThat(AiAuthorityPolicy.mayCallModel(AiDeploymentMode.INTERNAL_ONLY, AiAction.EXPLAIN_INCIDENT)).isTrue();
    }

    @Test
    void mayApplyChange_requiresBothValidatorAndHuman() {
        assertThat(AiAuthorityPolicy.mayApplyChange(true, true)).isTrue();
        assertThat(AiAuthorityPolicy.mayApplyChange(true, false)).isFalse();
        assertThat(AiAuthorityPolicy.mayApplyChange(false, true)).isFalse();
        assertThat(AiAuthorityPolicy.mayApplyChange(false, false)).isFalse();
    }

    @Test
    void blockedDataCategories_coverCardDataSecretsAndRawPayloads() {
        assertThat(AiAuthorityPolicy.blockedDataCategories()).contains(
                AiSensitiveDataCategory.PAN,
                AiSensitiveDataCategory.CVV,
                AiSensitiveDataCategory.TRACK_DATA,
                AiSensitiveDataCategory.PROVIDER_CREDENTIAL,
                AiSensitiveDataCategory.WEBHOOK_SECRET,
                AiSensitiveDataCategory.SIGNATURE_HEADER,
                AiSensitiveDataCategory.PRIVATE_KEY,
                AiSensitiveDataCategory.RAW_PROVIDER_PAYLOAD,
                AiSensitiveDataCategory.RAW_DATABASE_ROW,
                AiSensitiveDataCategory.UNREDACTED_CUSTOMER_PII,
                AiSensitiveDataCategory.CROSS_TENANT_DATA);
        assertThat(AiAuthorityPolicy.blockedDataCategories()).containsExactlyInAnyOrder(AiSensitiveDataCategory.values());
    }
}
