package com.masonx.common.tenant;

/**
 * TEST/LIVE isolation mode — the canonical cross-service mode for the platform.
 * Gateway currently uses its own {@code ApiKeyMode} internally and maps to this
 * at the event boundary; new modules use this type directly.
 */
public enum Mode {
    TEST,
    LIVE
}
