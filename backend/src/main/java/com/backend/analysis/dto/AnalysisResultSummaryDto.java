package com.backend.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AnalysisResultSummaryDto {

    private Long frames;

    @JsonProperty("track_count")
    private Long trackCount;

    @JsonProperty("crossed_count")
    private Long crossedCount;

    @JsonProperty("violation_count")
    private Long violationCount;

    private Double fps;

    @JsonProperty("processing_ms")
    private Long processingMs;

    public Long getFrames() {
        return frames;
    }

    public void setFrames(Long frames) {
        this.frames = frames;
    }

    public Long getTrackCount() {
        return trackCount;
    }

    public void setTrackCount(Long trackCount) {
        this.trackCount = trackCount;
    }

    public Long getCrossedCount() {
        return crossedCount;
    }

    public void setCrossedCount(Long crossedCount) {
        this.crossedCount = crossedCount;
    }

    public Long getViolationCount() {
        return violationCount;
    }

    public void setViolationCount(Long violationCount) {
        this.violationCount = violationCount;
    }

    public Double getFps() {
        return fps;
    }

    public void setFps(Double fps) {
        this.fps = fps;
    }

    public Long getProcessingMs() {
        return processingMs;
    }

    public void setProcessingMs(Long processingMs) {
        this.processingMs = processingMs;
    }
}
