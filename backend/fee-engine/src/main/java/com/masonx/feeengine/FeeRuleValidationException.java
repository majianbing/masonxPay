package com.masonx.feeengine;

public class FeeRuleValidationException extends FeeEngineException {
    public FeeRuleValidationException(String message) {
        super(message);
    }

    public FeeRuleValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
