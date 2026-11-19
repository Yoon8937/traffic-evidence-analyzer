package com.backend.analysis;

import java.time.LocalDateTime;

public class FastApiAcceptanceResult {

    public static final String OUTCOME_ACCEPTED = "ACCEPTED";
    public static final String OUTCOME_CLIENT_ERROR = "CLIENT_ERROR";
    public static final String OUTCOME_SERVER_ERROR = "SERVER_ERROR";
    public static final String OUTCOME_CONNECTION_FAILED = "CONNECTION_FAILED";
    public static final String OUTCOME_TIMEOUT = "TIMEOUT";

    private String outcome;
    private int httpStatus;
    private LocalDateTime acceptedAt;
    private String errorCode;
    private String errorMessage;

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isAccepted() {
        return OUTCOME_ACCEPTED.equals(outcome);
    }
}
