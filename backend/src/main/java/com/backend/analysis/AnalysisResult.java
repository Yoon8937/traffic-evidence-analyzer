package com.backend.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "analysis_results")
public class AnalysisResult {

    @Id
    private UUID id;

    @Column(name = "analysis_job_id", nullable = false, unique = true)
    private UUID analysisJobId;

    @Column(name = "is_valid")
    private Boolean isValid;

    @Column(name = "violation_detected")
    private Boolean violationDetected;

    @Column(name = "summary")
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_data")
    private JsonNode resultData;

    @Column(name = "result_s3_key", length = 500)
    private String resultS3Key;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public AnalysisResult() {
    }

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAnalysisJobId() {
        return analysisJobId;
    }

    public void setAnalysisJobId(UUID analysisJobId) {
        this.analysisJobId = analysisJobId;
    }

    public Boolean getIsValid() {
        return isValid;
    }

    public void setIsValid(Boolean isValid) {
        this.isValid = isValid;
    }

    public Boolean getViolationDetected() {
        return violationDetected;
    }

    public void setViolationDetected(Boolean violationDetected) {
        this.violationDetected = violationDetected;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public JsonNode getResultData() {
        return resultData;
    }

    public void setResultData(JsonNode resultData) {
        this.resultData = resultData;
    }

    public String getResultS3Key() {
        return resultS3Key;
    }

    public void setResultS3Key(String resultS3Key) {
        this.resultS3Key = resultS3Key;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
