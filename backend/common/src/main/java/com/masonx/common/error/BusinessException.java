package com.masonx.common.error;

/**
 * Client-safe business rule violation. Carries a stable {@code code} and an HTTP
 * status so the boundary can render a redacted response — the original cause and
 * stack are never exposed to the client. Throw this (not raw runtime exceptions)
 * for expected, explainable failures.
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public BusinessException(String code, String message) {
        this(code, message, 422);
    }

    public BusinessException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
