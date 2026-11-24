package com.backend.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AnalysisResultEventDto {

    @JsonProperty("event_type")
    private String eventType;

    private Long frame;

    @JsonProperty("track_id")
    private Long trackId;

    @JsonProperty("evidence_s3_key")
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
