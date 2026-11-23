package com.backend.analysis;

import java.time.LocalDateTime;
import java.util.List;

public class FastApiJobStatusResponse {

    public static final String OUTCOME_REMOTE = "REMOTE";
    public static final String OUTCOME_NOT_FOUND = "NOT_FOUND";
    public static final String OUTCOME_UNAUTHORIZED = "UNAUTHORIZED";
    public static final String OUTCOME_CLIENT_ERROR = "CLIENT_ERROR";
    public static final String OUTCOME_SERVER_ERROR = "SERVER_ERROR";
    public static final String OUTCOME_TIMEOUT = "TIMEOUT";
    public static final String OUTCOME_CONNECTION_FAILED = "CONNECTION_FAILED";
    public static final String OUTCOME_INVALID = "INVALID_RESPONSE";

    private String outcome;
    private int httpStatus;
    private String jobId;
    private String status;
    private LocalDateTime acceptedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Boolean isValid;
    private FastApiJobSummary summary;
    private List<FastApiJobEvent> events;
    private String resultVideoS3Key;
    private Boolean callbackDelivered;
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

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Boolean getIsValid() {
        return isValid;
    }

    public void setIsValid(Boolean isValid) {
        this.isValid = isValid;
    }

    public FastApiJobSummary getSummary() {
        return summary;
    }

    public void setSummary(FastApiJobSummary summary) {
        this.summary = summary;
    }

    public List<FastApiJobEvent> getEvents() {
        return events;
    }

    public void setEvents(List<FastApiJobEvent> events) {
        this.events = events;
    }

    public String getResultVideoS3Key() {
        return resultVideoS3Key;
    }

    public void setResultVideoS3Key(String resultVideoS3Key) {
        this.resultVideoS3Key = resultVideoS3Key;
    }

    public Boolean getCallbackDelivered() {
        return callbackDelivered;
    }

    public void setCallbackDelivered(Boolean callbackDelivered) {
        this.callbackDelivered = callbackDelivered;
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
}
