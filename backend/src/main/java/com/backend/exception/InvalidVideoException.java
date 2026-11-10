package com.backend.exception;

public class InvalidVideoException extends RuntimeException {

    public InvalidVideoException(String message) {
        super(message);
    }
}
