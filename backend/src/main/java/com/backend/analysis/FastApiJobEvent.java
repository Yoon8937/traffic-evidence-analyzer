package com.backend.analysis;

public class FastApiJobEvent {

    private String eventType;
    private Long frame;
    private Long trackId;
    private String evidenceS3Key;

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Long getFrame() {
        return frame;
    }

    public void setFrame(Long frame) {
        this.frame = frame;
    }

    public Long getTrackId() {
        return trackId;
    }

    public void setTrackId(Long trackId) {
        this.trackId = trackId;
    }

    public String getEvidenceS3Key() {
        return evidenceS3Key;
    }

    public void setEvidenceS3Key(String evidenceS3Key) {
        this.evidenceS3Key = evidenceS3Key;
    }
}
