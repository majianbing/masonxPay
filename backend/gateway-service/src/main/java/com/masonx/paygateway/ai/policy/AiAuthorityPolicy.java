package com.masonx.paygateway.ai.policy;

import java.util.EnumSet;
import java.util.Set;

/**
 * Single executable statement of the AI safety and authority model (Phase AI0).
 *
 * <p>Invariants every AI workflow must hold, regardless of model provider or deployment mode:
 * <ul>
 *   <li>AI is advisory: every output is a draft until a deterministic validator and a human accept it.</li>
 *   <li>The deterministic routing engine is the only runtime decision maker for payments.</li>
 *   <li>Sensitive data is blocked in code before any model call.</li>
 *   <li>Tenant, TEST/LIVE mode, and RBAC scope are enforced by the gateway, never by the model.</li>
 * </ul>
 *
 * <p>Stateless by design so it can be asserted on from any module without Spring wiring.
 */
public final class AiAuthorityPolicy {

    private static final Set<AiAction> ADVISORY_ACTIONS = EnumSet.noneOf(AiAction.class);
    private static final Set<AiAction> FORBIDDEN_ACTIONS = EnumSet.noneOf(AiAction.class);

    static {
        for (AiAction action : AiAction.values()) {
            (action.isAdvisory() ? ADVISORY_ACTIONS : FORBIDDEN_ACTIONS).add(action);
        }
    }

    private AiAuthorityPolicy() {
    }

    public static Set<AiAction> advisoryActions() {
        return Set.copyOf(ADVISORY_ACTIONS);
    }

    public static Set<AiAction> forbiddenActions() {
        return Set.copyOf(FORBIDDEN_ACTIONS);
    }

    public static Set<AiSensitiveDataCategory> blockedDataCategories() {
        return EnumSet.allOf(AiSensitiveDataCategory.class);
    }

    public static boolean isPermitted(AiAction action) {
        return action.isAdvisory();
    }

    /** Guard for any code path that hands an action to an AI surface. */
    public static void requireAdvisory(AiAction action) {
        if (action.isForbidden()) {
            throw new AiAuthorityViolationException(action);
        }
    }

    /**
     * Whether a model may be called at all for the given mode. Deployment mode never
     * widens authority: a forbidden action stays forbidden even under {@link AiDeploymentMode#INTERNAL_ONLY}.
     */
    public static boolean mayCallModel(AiDeploymentMode mode, AiAction action) {
        requireAdvisory(action);
        return mode.allowsModelCalls();
    }

    /**
     * An applied change requires both a passing deterministic validation and a human approval.
     * AI output alone, or AI output plus only one of the two, is never sufficient.
     */
    public static boolean mayApplyChange(boolean validatorPassed, boolean humanApproved) {
        return validatorPassed && humanApproved;
    }
}
