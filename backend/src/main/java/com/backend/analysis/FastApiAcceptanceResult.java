package com.backend.analysis;

import java.time.LocalDateTime;

public class FastApiAcceptanceResult {

    public static final String OUTCOME_ACCEPTED = "ACCEPTED";
    public static final String OUTCOME_REMOTE_EXISTS = "REMOTE_EXISTS";
    public static final String OUTCOME_CLIENT_ERROR = "CLIENT_ERROR";
    public static final String OUTCOME_SERVER_ERROR = "SERVER_ERROR";
    public static final String OUTCOME_CONNECTION_FAILED = "CONNECTION_FAILED";
    public static final String OUTCOME_TIMEOUT = "TIMEOUT";
    public static final String OUTCOME_INVALID_RESPONSE = "INVALID_RESPONSE";

    public static final String ERROR_QUEUE_FULL = "QUEUE_FULL";
    public static final String ERROR_WORKER_UNAVAILABLE = "WORKER_UNAVAILABLE";
    public static final String ERROR_SERVER = "FASTAPI_SERVER_ERROR";
    public static final String ERROR_CLIENT = "FASTAPI_CLIENT_ERROR";
    public static final String ERROR_TIMEOUT = "FASTAPI_TIMEOUT";
    public static final String ERROR_CONNECTION = "FASTAPI_CONNECTION_FAILED";
    public static final String ERROR_REQUEST_FAILED = "FASTAPI_REQUEST_FAILED";
    public static final String ERROR_RESPONSE_INVALID = "FASTAPI_RESPONSE_INVALID";

    private String outcome;
    private int httpStatus;
    private String remoteStatus;
    private String responseJobId;
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

    public String getRemoteStatus() {
        return remoteStatus;
    }

    public void setRemoteStatus(String remoteStatus) {
        this.remoteStatus = remoteStatus;
    }

    public String getResponseJobId() {
        return responseJobId;
    }

    public void setResponseJobId(String responseJobId) {
        this.responseJobId = responseJobId;
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

    public boolean isRemoteExists() {
        return OUTCOME_REMOTE_EXISTS.equals(outcome);
    }

    public boolean isRetryable() {
        return OUTCOME_SERVER_ERROR.equals(outcome)
                || OUTCOME_TIMEOUT.equals(outcome)
                || OUTCOME_CONNECTION_FAILED.equals(outcome);
    }
}
