package com.masonx.paygateway.ai.policy;

import com.masonx.common.error.BusinessException;

/** Raised when code tries to let an AI surface perform a {@link AiAuthority#FORBIDDEN} action. */
public class AiAuthorityViolationException extends BusinessException {

    public static final String CODE = "ai_authority_violation";

    private final AiAction action;

    public AiAuthorityViolationException(AiAction action) {
        super(CODE, "AI surfaces may not perform " + action + "; it is reserved for deterministic code or human approval", 403);
        this.action = action;
    }

    public AiAction action() {
        return action;
    }
}
