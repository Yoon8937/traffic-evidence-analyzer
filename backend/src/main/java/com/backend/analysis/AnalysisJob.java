package com.backend.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "analysis_jobs")
public class AnalysisJob {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** Resends after the first submit. Callback retry is a separate policy. */
    public static final int MAX_DISPATCH_RESENDS = 2;

    public static final String ERROR_DISPATCH_RETRY_EXHAUSTED = "DISPATCH_RETRY_EXHAUSTED";
    public static final String ERROR_RECOVERY_NOT_IMPLEMENTED = "RECOVERY_NOT_IMPLEMENTED";
    public static final String ERROR_GET_NOT_FOUND = "GET_NOT_FOUND";
    public static final String ERROR_GET_NOT_FOUND_EXHAUSTED = "GET_NOT_FOUND_EXHAUSTED";
    public static final String ERROR_PROCESSING_TIMEOUT = "PROCESSING_TIMEOUT";

    public static final String SCHEDULER_TASK_DISPATCH = "DISPATCH";
    public static final String SCHEDULER_TASK_RECOVERY = "RECOVERY";
    public static final String SCHEDULER_TASK_TIMEOUT = "TIMEOUT";

    @Id
    private UUID id;

    @Column(name = "video_id", nullable = false)
    private UUID videoId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AnalysisJob() {
    }

    public static AnalysisJob pendingJob(UUID videoId) {
        AnalysisJob job = new AnalysisJob();
        job.setId(UUID.randomUUID());
        job.setVideoId(videoId);
        job.setStatus(STATUS_PENDING);
        job.setRetryCount(0);
        return job;
    }

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.retryCount == null) {
            this.retryCount = 0;
        }
        if (this.status == null) {
            this.status = STATUS_PENDING;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getVideoId() {
        return videoId;
    }

    public void setVideoId(UUID videoId) {
        this.videoId = videoId;
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

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
