package com.backend.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public class AnalysisJobResultCallbackRequest {

    @JsonProperty("job_id")
    private UUID jobId;

    private String status;

    @JsonProperty("finished_at")
    private String finishedAt;

    @JsonProperty("is_valid")
    private Boolean isValid;

    private AnalysisResultSummaryDto summary;

    private List<AnalysisResultEventDto> events;

    @JsonProperty("result_video_s3_key")
    private String resultVideoS3Key;

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Boolean getIsValid() {
        return isValid;
    }

    public void setIsValid(Boolean isValid) {
        this.isValid = isValid;
    }

    public AnalysisResultSummaryDto getSummary() {
        return summary;
    }

    public void setSummary(AnalysisResultSummaryDto summary) {
        this.summary = summary;
    }

    public List<AnalysisResultEventDto> getEvents() {
        return events;
    }

    public void setEvents(List<AnalysisResultEventDto> events) {
        this.events = events;
    }

    public String getResultVideoS3Key() {
        return resultVideoS3Key;
    }

    public void setResultVideoS3Key(String resultVideoS3Key) {
        this.resultVideoS3Key = resultVideoS3Key;
    }
}
