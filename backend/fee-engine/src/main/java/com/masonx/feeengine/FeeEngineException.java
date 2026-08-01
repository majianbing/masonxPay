package com.masonx.feeengine;

public class FeeEngineException extends RuntimeException {
    public FeeEngineException(String message) {
        super(message);
    }

    public FeeEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
