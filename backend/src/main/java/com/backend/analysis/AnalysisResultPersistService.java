package com.backend.analysis;

import com.backend.exception.CallbackConflictException;
import com.backend.exception.CallbackValidationException;
import com.backend.exception.JobNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AnalysisResultPersistService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisResultPersistService.class);
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    private final AnalysisJobRepository analysisJobRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final ObjectMapper objectMapper;

    public AnalysisResultPersistService(
            AnalysisJobRepository analysisJobRepository,
            AnalysisResultRepository analysisResultRepository,
            ObjectMapper objectMapper
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.analysisResultRepository = analysisResultRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void persistFromCallback(UUID pathJobId, JsonNode payload) {
        LocalDateTime finishedAt = parseRequiredUtc(text(payload, "finished_at"), "finished_at");
        persistLocked(pathJobId, payload, finishedAt);
    }

    @Transactional
    public void persistFromGet(UUID jobId, FastApiJobStatusResponse response) {
        JsonNode payload = toGetPayload(jobId, response);
        LocalDateTime finishedAt = response.getFinishedAt();
        persistLocked(jobId, payload, finishedAt);
    }

    private void persistLocked(UUID jobId, JsonNode payload, LocalDateTime finishedAt) {
        AnalysisJob job = analysisJobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new JobNotFoundException("analysis job not found"));
        if (job.getId() != null) {
            MDC.put("job_id", job.getId().toString());
        }
        if (job.getVideoId() != null) {
            MDC.put("video_id", job.getVideoId().toString());
        }
        MDC.put("status", job.getStatus());
        applyCompleted(job, payload, finishedAt);
    }

    private void applyCompleted(AnalysisJob job, JsonNode payload, LocalDateTime finishedAt) {
        Optional<AnalysisResult> existing = analysisResultRepository.findByAnalysisJobId(job.getId());

        if (AnalysisJob.STATUS_FAILED.equals(job.getStatus())) {
            log.warn("ignored COMPLETED result because job is already FAILED");
            throw new CallbackConflictException(
                    "JOB_STATUS_CONFLICT",
                    "이미 FAILED인 작업은 COMPLETED로 변경할 수 없습니다."
            );
        }

        if (existing.isPresent()) {
            if (sameResult(existing.get(), payload)) {
                log.info("duplicate COMPLETED result");
                return;
            }
            log.warn("completed result payload conflict");
            throw new CallbackConflictException(
                    "RESULT_CONFLICT",
                    "이미 저장된 분석 결과와 다른 결과가 수신되었습니다."
            );
        }

        if (AnalysisJob.STATUS_COMPLETED.equals(job.getStatus())) {
            log.error("completed job is missing analysis result");
            throw new CallbackConflictException(
                    "RESULT_INCONSISTENT",
                    "COMPLETED 작업에 분석 결과가 없습니다."
            );
        }

        analysisResultRepository.save(toResult(job.getId(), payload));
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        if (finishedAt != null) {
            job.setFinishedAt(finishedAt);
        }
        job.setErrorCode(null);
        job.setErrorMessage(null);
        analysisJobRepository.save(job);
        MDC.put("status", AnalysisJob.STATUS_COMPLETED);
        log.info("job status changed to COMPLETED");
    }

    JsonNode toGetPayload(UUID jobId, FastApiJobStatusResponse response) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("job_id", jobId.toString());
        payload.put("status", AnalysisJob.STATUS_COMPLETED);
        if (response.getFinishedAt() != null) {
            payload.put("finished_at", response.getFinishedAt().format(UTC_FORMATTER));
        }
        if (response.getIsValid() != null) {
            payload.put("is_valid", response.getIsValid());
        }
        if (response.getSummary() != null) {
            payload.set("summary", summaryNode(response.getSummary()));
        }
        if (response.getEvents() != null) {
            ArrayNode events = payload.putArray("events");
            for (FastApiJobEvent event : response.getEvents()) {
                events.add(eventNode(event));
            }
        }
        if (response.getResultVideoS3Key() != null) {
            payload.put("result_video_s3_key", response.getResultVideoS3Key());
        }
        return payload;
    }

    private ObjectNode summaryNode(FastApiJobSummary summary) {
        ObjectNode node = objectMapper.createObjectNode();
        putLong(node, "frames", summary.getFrames());
        putLong(node, "track_count", summary.getTrackCount());
        putLong(node, "crossed_count", summary.getCrossedCount());
        putLong(node, "violation_count", summary.getViolationCount());
        if (summary.getFps() != null) {
            node.put("fps", summary.getFps());
        }
        putLong(node, "processing_ms", summary.getProcessingMs());
        return node;
    }

    private ObjectNode eventNode(FastApiJobEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        if (event.getEventType() != null) {
            node.put("event_type", event.getEventType());
        }
        putLong(node, "frame", event.getFrame());
        putLong(node, "track_id", event.getTrackId());
        if (event.getEvidenceS3Key() != null) {
            node.put("evidence_s3_key", event.getEvidenceS3Key());
        }
        return node;
    }

    private void putLong(ObjectNode node, String field, Long value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private AnalysisResult toResult(UUID jobId, JsonNode payload) {
        AnalysisResult result = new AnalysisResult();
        result.setId(UUID.randomUUID());
        result.setAnalysisJobId(jobId);
        result.setIsValid(booleanOrNull(payload.get("is_valid")));
        result.setResultS3Key(text(payload, "result_video_s3_key"));
        result.setResultData(copy(payload));

        JsonNode summaryNode = payload.get("summary");
        if (summaryNode == null || summaryNode.isNull()) {
            result.setSummary(null);
            result.setViolationDetected(null);
        } else {
            result.setSummary(objectMapper.writeValueAsString(summaryNode));
            result.setViolationDetected(violationDetected(summaryNode));
        }
        return result;
    }

    private Boolean violationDetected(JsonNode summary) {
        JsonNode count = summary.get("violation_count");
        if (count == null || count.isNull() || !count.isNumber()) {
            return null;
        }
        return count.longValue() > 0;
    }

    private boolean sameResult(AnalysisResult existing, JsonNode payload) {
        JsonNode stored = existing.getResultData();
        if (stored != null) {
            return jsonEquals(stored.get("is_valid"), payload.get("is_valid"))
                    && jsonEquals(stored.get("summary"), payload.get("summary"))
                    && jsonEquals(stored.get("events"), payload.get("events"))
                    && jsonEquals(stored.get("result_video_s3_key"), payload.get("result_video_s3_key"));
        }
        return Objects.equals(existing.getIsValid(), booleanOrNull(payload.get("is_valid")))
                && Objects.equals(existing.getResultS3Key(), text(payload, "result_video_s3_key"))
                && Objects.equals(existing.getSummary(), summaryText(payload.get("summary")));
    }

    private String summaryText(JsonNode summaryNode) {
        if (summaryNode == null || summaryNode.isNull()) {
            return null;
        }
        return objectMapper.writeValueAsString(summaryNode);
    }

    private LocalDateTime parseRequiredUtc(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new CallbackValidationException("VALIDATION_FAILED", field + "는 필수입니다.");
        }
        LocalDateTime parsed = UtcDateTimes.parse(raw);
        if (parsed == null) {
            throw new CallbackValidationException("INVALID_TIMESTAMP", field + " 형식이 올바르지 않습니다.");
        }
        return parsed;
    }

    private JsonNode copy(JsonNode payload) {
        return objectMapper.readTree(objectMapper.writeValueAsString(payload));
    }

    private boolean jsonEquals(JsonNode left, JsonNode right) {
        return semanticallyEqual(left, right);
    }

    private boolean semanticallyEqual(JsonNode left, JsonNode right) {
        if (isAbsent(left) && isAbsent(right)) {
            return true;
        }
        if (isAbsent(left) || isAbsent(right)) {
            return false;
        }
        if (left.isObject() && right.isObject()) {
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
            left.propertyNames().forEach(names::add);
            right.propertyNames().forEach(names::add);
            for (String name : names) {
                if (!semanticallyEqual(left.get(name), right.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int i = 0; i < left.size(); i++) {
                if (!semanticallyEqual(left.get(i), right.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        return left.equals(right);
    }

    private boolean isAbsent(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private Boolean booleanOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isBoolean()) {
            return null;
        }
        return node.booleanValue();
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
