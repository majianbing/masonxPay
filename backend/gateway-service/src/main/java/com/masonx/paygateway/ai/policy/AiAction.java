package com.masonx.paygateway.ai.policy;

/**
 * Closed catalog of actions an AI surface could be asked to take, each tagged with
 * the authority MasonXPay grants it. The {@link AiAuthority#FORBIDDEN} entries are the
 * hard boundary: validators and humans sit between every advisory output and any
 * applied change, and the deterministic routing engine is the only runtime decision maker.
 */
public enum AiAction {

    ANSWER_DOCUMENTATION_QUESTION(AiAuthority.ADVISORY),
    ANALYZE_TELEMETRY(AiAuthority.ADVISORY),
    EXPLAIN_INCIDENT(AiAuthority.ADVISORY),
    RECOMMEND_ROUTING_POLICY_CHANGE(AiAuthority.ADVISORY),
    DRAFT_CONFIGURATION_CHANGE(AiAuthority.ADVISORY),

    AUTHORIZE_PAYMENT(AiAuthority.FORBIDDEN),
    DECLINE_PAYMENT(AiAuthority.FORBIDDEN),
    ROUTE_PAYMENT(AiAuthority.FORBIDDEN),
    CAPTURE_PAYMENT(AiAuthority.FORBIDDEN),
    REFUND_PAYMENT(AiAuthority.FORBIDDEN),
    MUTATE_ROUTING_RULES(AiAuthority.FORBIDDEN),
    APPLY_CONFIGURATION_CHANGE(AiAuthority.FORBIDDEN),
    APPROVE_PROPOSAL(AiAuthority.FORBIDDEN),
    BYPASS_TENANT_SCOPE(AiAuthority.FORBIDDEN),
    BYPASS_MODE_SCOPE(AiAuthority.FORBIDDEN),
    BYPASS_RBAC(AiAuthority.FORBIDDEN),
    BYPASS_VALIDATOR(AiAuthority.FORBIDDEN),
    EXECUTE_ARBITRARY_SQL(AiAuthority.FORBIDDEN),
    EXECUTE_ARBITRARY_HTTP(AiAuthority.FORBIDDEN),
    ACCESS_FILESYSTEM(AiAuthority.FORBIDDEN),
    READ_SENSITIVE_DATA(AiAuthority.FORBIDDEN);

    private final AiAuthority authority;

    AiAction(AiAuthority authority) {
        this.authority = authority;
    }

    public AiAuthority authority() {
        return authority;
    }

    public boolean isAdvisory() {
        return authority == AiAuthority.ADVISORY;
    }

    public boolean isForbidden() {
        return authority == AiAuthority.FORBIDDEN;
    }
}
