package com.backend.analysis;

import com.backend.config.TraceIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class FastApiAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(FastApiAnalysisClient.class);
    private static final String PATH = "/internal/analysis-jobs";
    static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String internalToken;

    public FastApiAnalysisClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.fastapi.base-url}") String baseUrl,
            @Value("${app.fastapi.internal-token:}") String internalToken
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    public FastApiAcceptanceResult submit(UUID jobId, UUID videoId, String objectKey) {
        Map<String, String> body = new LinkedHashMap<String, String>();
        body.put("job_id", jobId.toString());
        body.put("video_id", videoId.toString());
        body.put("object_key", objectKey);

        String json = objectMapper.writeValueAsString(body);
        String url = baseUrl + PATH;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(TraceIdFilter.HEADER_TRACE_ID, currentTraceId());
        headers.set(HEADER_INTERNAL_TOKEN, internalToken);
        HttpEntity<String> entity = new HttpEntity<String>(json, headers);

        long started = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            putDuration(System.currentTimeMillis() - started);
            log.info("FastAPI request finished. httpStatus={}", response.getStatusCode().value());
            return toResult(response.getStatusCode().value(), response.getBody());
        } catch (HttpStatusCodeException e) {
            putDuration(System.currentTimeMillis() - started);
            HttpStatusCode statusCode = e.getStatusCode();
            log.info("FastAPI request finished. httpStatus={}", statusCode.value());
            return toResult(statusCode.value(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            putDuration(System.currentTimeMillis() - started);
            return toNetworkFailure(e);
        }
    }

    public FastApiJobStatusResponse getJobStatus(UUID jobId) {
        String url = baseUrl + PATH + "/" + jobId;
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceIdFilter.HEADER_TRACE_ID, currentTraceId());
        headers.set(HEADER_INTERNAL_TOKEN, internalToken);
        HttpEntity<Void> entity = new HttpEntity<Void>(headers);

        long started = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            putDuration(System.currentTimeMillis() - started);
            log.info("FastAPI status request finished. httpStatus={}", response.getStatusCode().value());
            return toStatusResult(jobId, response.getStatusCode().value(), response.getBody());
        } catch (HttpStatusCodeException e) {
            putDuration(System.currentTimeMillis() - started);
            log.info("FastAPI status request finished. httpStatus={}", e.getStatusCode().value());
            return toStatusResult(jobId, e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            putDuration(System.currentTimeMillis() - started);
            return toStatusNetworkFailure(jobId, e);
        }
    }

    private FastApiJobStatusResponse toStatusResult(UUID jobId, int status, String body) {
        if (status == 200) {
            return parseRemoteStatus(jobId, body);
        }
        if (status == 404) {
            return notFound(jobId, body);
        }
        if (status == 401) {
            return statusError(jobId, status, FastApiJobStatusResponse.OUTCOME_UNAUTHORIZED);
        }
        if (status >= 400 && status < 500) {
            return statusError(jobId, status, FastApiJobStatusResponse.OUTCOME_CLIENT_ERROR);
        }
        if (status >= 500) {
            return statusError(jobId, status, FastApiJobStatusResponse.OUTCOME_SERVER_ERROR);
        }
        return invalidGet(jobId, status);
    }

    private FastApiJobStatusResponse parseRemoteStatus(UUID jobId, String body) {
        JsonNode root = tryReadObject(body);
        if (root == null) {
            return invalidGet(jobId, 200);
        }
        String bodyJobId = text(root, "job_id");
        if (bodyJobId != null && jobId != null && !bodyJobId.equals(jobId.toString())) {
            FastApiJobStatusResponse mismatch = invalidGet(jobId, 200);
            mismatch.setJobId(bodyJobId);
            return mismatch;
        }
        String remoteStatus = text(root, "status");
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setHttpStatus(200);
        response.setOutcome(FastApiJobStatusResponse.OUTCOME_REMOTE);
        response.setJobId(bodyJobId == null && jobId != null ? jobId.toString() : bodyJobId);
        response.setStatus(remoteStatus);
        if ("ACCEPTED".equals(remoteStatus)) {
            response.setAcceptedAt(parseUtcTimestamp(text(root, "accepted_at")));
            return response;
        }
        if ("PROCESSING".equals(remoteStatus)) {
            response.setStartedAt(parseUtcTimestamp(text(root, "started_at")));
            return response;
        }
        if ("COMPLETED".equals(remoteStatus)) {
            if (!fillCompleted(response, root)) {
                return invalidGet(jobId, 200);
            }
            return response;
        }
        if ("FAILED".equals(remoteStatus)) {
            response.setErrorCode(text(root, "error_code"));
            response.setErrorMessage(text(root, "error_message"));
            response.setFinishedAt(parseUtcTimestamp(text(root, "finished_at")));
            response.setCallbackDelivered(booleanOrNull(root.get("callback_delivered")));
            return response;
        }
        return invalidGet(jobId, 200);
    }

    private boolean fillCompleted(FastApiJobStatusResponse response, JsonNode root) {
        response.setFinishedAt(parseUtcTimestamp(text(root, "finished_at")));
        response.setIsValid(booleanOrNull(root.get("is_valid")));
        JsonNode summaryNode = root.get("summary");
        if (summaryNode == null || summaryNode.isNull()) {
            response.setSummary(null);
        } else if (!summaryNode.isObject()) {
            return false;
        } else {
            response.setSummary(readSummary(summaryNode));
        }
        JsonNode eventsNode = root.get("events");
        if (eventsNode == null || eventsNode.isNull()) {
            response.setEvents(null);
        } else if (!eventsNode.isArray()) {
            return false;
        } else {
            List<FastApiJobEvent> events = new ArrayList<FastApiJobEvent>();
            for (JsonNode eventNode : eventsNode) {
                if (eventNode == null || !eventNode.isObject()) {
                    return false;
                }
                events.add(readEvent(eventNode));
            }
            response.setEvents(events);
        }
        response.setResultVideoS3Key(text(root, "result_video_s3_key"));
        response.setCallbackDelivered(booleanOrNull(root.get("callback_delivered")));
        return true;
    }

    private FastApiJobSummary readSummary(JsonNode node) {
        FastApiJobSummary summary = new FastApiJobSummary();
        summary.setFrames(longOrNull(node.get("frames")));
        summary.setTrackCount(longOrNull(node.get("track_count")));
        summary.setCrossedCount(longOrNull(node.get("crossed_count")));
        summary.setViolationCount(longOrNull(node.get("violation_count")));
        summary.setFps(doubleOrNull(node.get("fps")));
        summary.setProcessingMs(longOrNull(node.get("processing_ms")));
        return summary;
    }

    private FastApiJobEvent readEvent(JsonNode node) {
        FastApiJobEvent event = new FastApiJobEvent();
        event.setEventType(text(node, "event_type"));
        event.setFrame(longOrNull(node.get("frame")));
        event.setTrackId(longOrNull(node.get("track_id")));
        event.setEvidenceS3Key(text(node, "evidence_s3_key"));
        return event;
    }

    private FastApiJobStatusResponse notFound(UUID jobId, String body) {
        FastApiJobStatusResponse response = statusError(jobId, 404, FastApiJobStatusResponse.OUTCOME_NOT_FOUND);
        JsonNode root = tryReadObject(body);
        if (root != null) {
            response.setErrorCode(text(root, "error_code"));
            response.setErrorMessage(text(root, "message"));
        }
        return response;
    }

    private FastApiJobStatusResponse statusError(UUID jobId, int status, String outcome) {
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setHttpStatus(status);
        response.setOutcome(outcome);
        if (jobId != null) {
            response.setJobId(jobId.toString());
        }
        return response;
    }

    private FastApiJobStatusResponse invalidGet(UUID jobId, int status) {
        return statusError(jobId, status, FastApiJobStatusResponse.OUTCOME_INVALID);
    }

    private FastApiJobStatusResponse toStatusNetworkFailure(UUID jobId, ResourceAccessException e) {
        if (isTimeout(e)) {
            return statusError(jobId, 0, FastApiJobStatusResponse.OUTCOME_TIMEOUT);
        }
        return statusError(jobId, 0, FastApiJobStatusResponse.OUTCOME_CONNECTION_FAILED);
    }

    private FastApiAcceptanceResult toResult(int status, String body) {
        if (status == 202) {
            return toAcceptedResponse(body);
        }
        if (status == 503) {
            return toUnavailable(body);
        }
        if (status >= 400 && status < 500) {
            return clientError(status);
        }
        if (status >= 500) {
            return serverError(status);
        }
        return invalidResponse(status);
    }

    private FastApiAcceptanceResult toAcceptedResponse(String body) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setHttpStatus(202);
        JsonNode root = tryReadObject(body);
        if (root == null) {
            return invalidResponse(202);
        }
        result.setResponseJobId(text(root, "job_id"));
        String remoteStatus = text(root, "status");
        result.setRemoteStatus(remoteStatus);
        if ("ACCEPTED".equals(remoteStatus)) {
            LocalDateTime acceptedAt = readAcceptedAt(root);
            if (acceptedAt == null) {
                return invalidResponse(202);
            }
            result.setOutcome(FastApiAcceptanceResult.OUTCOME_ACCEPTED);
            result.setAcceptedAt(acceptedAt);
            return result;
        }
        if ("PROCESSING".equals(remoteStatus) || "COMPLETED".equals(remoteStatus) || "FAILED".equals(remoteStatus)) {
            result.setOutcome(FastApiAcceptanceResult.OUTCOME_REMOTE_EXISTS);
            return result;
        }
        return invalidResponse(202);
    }

    private FastApiAcceptanceResult toUnavailable(String body) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setHttpStatus(503);
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_SERVER_ERROR);
        JsonNode root = tryReadObject(body);
        String code = root == null ? null : text(root, "error_code");
        String message = root == null ? null : text(root, "message");
        if (FastApiAcceptanceResult.ERROR_QUEUE_FULL.equals(code)
                || FastApiAcceptanceResult.ERROR_WORKER_UNAVAILABLE.equals(code)) {
            result.setErrorCode(code);
        } else {
            result.setErrorCode(FastApiAcceptanceResult.ERROR_SERVER);
        }
        if (message == null) {
            result.setErrorMessage("FastAPI returned HTTP 503");
        } else {
            result.setErrorMessage(message);
        }
        return result;
    }

    private FastApiAcceptanceResult clientError(int status) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setHttpStatus(status);
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR);
        result.setErrorCode(FastApiAcceptanceResult.ERROR_CLIENT);
        result.setErrorMessage("FastAPI returned HTTP " + status);
        return result;
    }

    private FastApiAcceptanceResult serverError(int status) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setHttpStatus(status);
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_SERVER_ERROR);
        result.setErrorCode(FastApiAcceptanceResult.ERROR_SERVER);
        result.setErrorMessage("FastAPI returned HTTP " + status);
        return result;
    }

    private FastApiAcceptanceResult invalidResponse(int status) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setHttpStatus(status);
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_INVALID_RESPONSE);
        result.setErrorCode(FastApiAcceptanceResult.ERROR_RESPONSE_INVALID);
        result.setErrorMessage("FastAPI returned HTTP " + status);
        return result;
    }

    private FastApiAcceptanceResult toNetworkFailure(ResourceAccessException e) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setHttpStatus(0);
        if (isTimeout(e)) {
            result.setOutcome(FastApiAcceptanceResult.OUTCOME_TIMEOUT);
            result.setErrorCode(FastApiAcceptanceResult.ERROR_TIMEOUT);
            result.setErrorMessage("FastAPI request timed out");
            return result;
        }
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_CONNECTION_FAILED);
        result.setErrorCode(FastApiAcceptanceResult.ERROR_CONNECTION);
        result.setErrorMessage("FastAPI connection failed");
        return result;
    }

    private boolean isTimeout(ResourceAccessException e) {
        Throwable current = e;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("timed out") || lower.contains("timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private LocalDateTime readAcceptedAt(JsonNode root) {
        return parseUtcTimestamp(text(root, "accepted_at"));
    }

    private LocalDateTime parseUtcTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            if (parsed.getOffset().getTotalSeconds() != 0) {
                return null;
            }
            return parsed.toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Long longOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        double asDouble = node.doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble) || asDouble != Math.rint(asDouble)) {
            return null;
        }
        return node.longValue();
    }

    private Double doubleOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        double asDouble = node.doubleValue();
        if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
            return null;
        }
        return asDouble;
    }

    private Boolean booleanOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isBoolean()) {
            return null;
        }
        return node.booleanValue();
    }

    private JsonNode tryReadObject(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return null;
            }
            return root;
        } catch (Exception e) {
            log.warn("FastAPI response body could not be parsed");
            return null;
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

    private String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            MDC.put(TraceIdFilter.TRACE_ID, traceId);
        }
        return traceId;
    }

    private void putDuration(long durationMs) {
        MDC.put("duration_ms", String.valueOf(durationMs));
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
