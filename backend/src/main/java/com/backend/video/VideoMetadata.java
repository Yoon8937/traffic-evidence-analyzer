package com.backend.video;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class VideoMetadata {

    private boolean validVideo;
    private BigDecimal durationSeconds;
    private Integer width;
    private Integer height;
    private BigDecimal frameRate;
    private LocalDateTime recordedAt;

    public boolean isValidVideo() {
        return validVideo;
    }

    public void setValidVideo(boolean validVideo) {
        this.validVideo = validVideo;
    }

    public BigDecimal getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(BigDecimal durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public BigDecimal getFrameRate() {
        return frameRate;
    }

    public void setFrameRate(BigDecimal frameRate) {
        this.frameRate = frameRate;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }
}
