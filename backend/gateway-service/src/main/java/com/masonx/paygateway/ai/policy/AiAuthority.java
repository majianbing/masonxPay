package com.masonx.paygateway.ai.policy;

/** What an AI surface is allowed to do with an action: suggest it, or never touch it. */
public enum AiAuthority {
    /** AI may perform the action; its output is a draft until a validator and a human accept it. */
    ADVISORY,
    /** AI may never perform the action, under any deployment mode or role. Only deterministic code or humans may. */
    FORBIDDEN
}
