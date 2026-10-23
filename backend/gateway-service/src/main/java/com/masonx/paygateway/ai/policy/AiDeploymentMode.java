package com.masonx.paygateway.ai.policy;

/**
 * Where model calls may go and how much evidence may cross the boundary.
 * {@link #DISABLED} must keep every deterministic workflow (incident detection,
 * validation, human review, rollback) fully functional with no model call at all.
 */
public enum AiDeploymentMode {

    /** Hosted model providers; redacted and aggregated evidence only. Default SaaS posture. */
    EXTERNAL_REDACTED(true, true),

    /** Hosted providers under stricter vendor agreements; redacted evidence plus explicitly approved limited context. */
    EXTERNAL_RESTRICTED(true, true),

    /** Self-hosted or internal model gateway; broader but still policy-filtered context. */
    INTERNAL_ONLY(true, false),

    /** No model calls. Deterministic detection, validator, dashboard workflow, and rollback still work. */
    DISABLED(false, false);

    private final boolean modelCallsAllowed;
    private final boolean externalProviderAllowed;

    AiDeploymentMode(boolean modelCallsAllowed, boolean externalProviderAllowed) {
        this.modelCallsAllowed = modelCallsAllowed;
        this.externalProviderAllowed = externalProviderAllowed;
    }

    public boolean allowsModelCalls() {
        return modelCallsAllowed;
    }

    public boolean allowsExternalProvider() {
        return externalProviderAllowed;
    }

    /** Parses the kebab-case form used in configuration and docs, e.g. {@code external-redacted}. */
    public static AiDeploymentMode fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return DISABLED;
        }
        return AiDeploymentMode.valueOf(value.trim().toUpperCase().replace('-', '_'));
    }

    public String configValue() {
        return name().toLowerCase().replace('_', '-');
    }
}
