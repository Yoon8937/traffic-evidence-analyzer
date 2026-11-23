package com.backend.analysis;

public class FastApiJobSummary {

    private Long frames;
    private Long trackCount;
    private Long crossedCount;
    private Long violationCount;
    private Double fps;
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
