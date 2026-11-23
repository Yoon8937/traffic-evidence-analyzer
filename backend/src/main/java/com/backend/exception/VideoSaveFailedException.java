package com.backend.exception;

public class VideoSaveFailedException extends RuntimeException {

    public VideoSaveFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
