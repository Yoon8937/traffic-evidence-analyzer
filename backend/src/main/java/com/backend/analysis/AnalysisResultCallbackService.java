package com.backend.analysis;

import com.backend.exception.CallbackValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@Service
public class AnalysisResultCallbackService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultCallbackService.class);

    private final AnalysisResultPersistService analysisResultPersistService;

    public AnalysisResultCallbackService(AnalysisResultPersistService analysisResultPersistService) {
        this.analysisResultPersistService = analysisResultPersistService;
    }

    public void handleResult(UUID pathJobId, JsonNode payload) {
        validateRequest(pathJobId, payload);
        log.info("analysis result callback received. callbackStatus={}", text(payload, "status"));
        analysisResultPersistService.persistFromCallback(pathJobId, payload);
    }

    private void validateRequest(UUID pathJobId, JsonNode payload) {
        if (payload == null || payload.isNull() || !payload.isObject()) {
            throw new CallbackValidationException("VALIDATION_FAILED", "요청 본문이 필요합니다.");
        }
        UUID bodyJobId = parseUuid(payload.get("job_id"), "job_id");
        if (!bodyJobId.equals(pathJobId)) {
            throw new CallbackValidationException("JOB_ID_MISMATCH", "URL job_id와 본문 job_id가 일치하지 않습니다.");
        }
        String status = text(payload, "status");
        if (status == null || status.isBlank()) {
            throw new CallbackValidationException("VALIDATION_FAILED", "status는 필수입니다.");
        }
        if (!AnalysisJob.STATUS_COMPLETED.equals(status)) {
            throw new CallbackValidationException("UNSUPPORTED_STATUS", "지원하지 않는 status입니다.");
        }
        JsonNode summary = payload.get("summary");
        if (summary != null && !summary.isNull() && !summary.isObject()) {
            throw new CallbackValidationException("VALIDATION_FAILED", "summary 형식이 올바르지 않습니다.");
        }
        JsonNode events = payload.get("events");
        if (events != null && !events.isNull() && !events.isArray()) {
            throw new CallbackValidationException("VALIDATION_FAILED", "events 형식이 올바르지 않습니다.");
        }
        JsonNode isValid = payload.get("is_valid");
        if (isValid != null && !isValid.isNull() && !isValid.isBoolean()) {
            throw new CallbackValidationException("VALIDATION_FAILED", "is_valid 형식이 올바르지 않습니다.");
        }
    }

    private UUID parseUuid(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            throw new CallbackValidationException("VALIDATION_FAILED", field + "는 필수입니다.");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new CallbackValidationException("VALIDATION_FAILED", field + " 형식이 올바르지 않습니다.");
        }
    }

    private String text(JsonNode root, String field) {
        if (root == null) {
            return null;
        }
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
