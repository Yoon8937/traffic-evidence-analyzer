package com.backend.exception;

public class CallbackValidationException extends RuntimeException {

    private final String code;

    public CallbackValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
