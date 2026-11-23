package com.backend.exception;

public class FfprobeExecutionException extends RuntimeException {

    public FfprobeExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public FfprobeExecutionException(String message) {
        super(message);
    }
}
