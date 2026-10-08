package com.backend.exception;

public class VideoValidationException extends RuntimeException {

    private final String code;

    public VideoValidationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
