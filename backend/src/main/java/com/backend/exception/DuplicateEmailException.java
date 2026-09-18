package com.backend.exception;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("이미 사용 중인 email입니다.");
    }
}
