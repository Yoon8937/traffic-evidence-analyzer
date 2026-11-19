package com.backend.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatusCode;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class FastApiAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(FastApiAnalysisClient.class);
    private static final String PATH = "/internal/analysis-jobs";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public FastApiAnalysisClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.fastapi.base-url}") String baseUrl
    ) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(baseUrl);
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
        HttpEntity<String> entity = new HttpEntity<String>(json, headers);

        long started = System.currentTimeMillis();
        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
            long durationMs = System.currentTimeMillis() - started;
            putDuration(durationMs);
            log.info("FastAPI request sent successfully");
            return toResult(response.getStatusCode().value(), response.getBody());
        } catch (HttpStatusCodeException e) {
            long durationMs = System.currentTimeMillis() - started;
            putDuration(durationMs);
            log.info("FastAPI request sent successfully");
            HttpStatusCode statusCode = e.getStatusCode();
            return toResult(statusCode.value(), e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            long durationMs = System.currentTimeMillis() - started;
            putDuration(durationMs);
            return toNetworkFailure(e);
        }
    }

    private FastApiAcceptanceResult toResult(int status, String body) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setHttpStatus(status);

        if (status == 409) {
            result.setOutcome(FastApiAcceptanceResult.OUTCOME_ACCEPTED);
            result.setAcceptedAt(readAcceptedAt(body, LocalDateTime.now()));
            return result;
        }
        if (status >= 200 && status < 300) {
            result.setOutcome(FastApiAcceptanceResult.OUTCOME_ACCEPTED);
            result.setAcceptedAt(readAcceptedAt(body, LocalDateTime.now()));
            return result;
        }
        if (status >= 400 && status < 500) {
            result.setOutcome(FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR);
            result.setErrorCode("FASTAPI_CLIENT_ERROR");
            result.setErrorMessage("FastAPI returned HTTP " + status);
            return result;
        }

        result.setOutcome(FastApiAcceptanceResult.OUTCOME_SERVER_ERROR);
        result.setErrorCode("FASTAPI_SERVER_ERROR");
        result.setErrorMessage("FastAPI returned HTTP " + status);
        return result;
    }

    private FastApiAcceptanceResult toNetworkFailure(ResourceAccessException e) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setHttpStatus(0);
        if (isTimeout(e)) {
            result.setOutcome(FastApiAcceptanceResult.OUTCOME_TIMEOUT);
            result.setErrorCode("FASTAPI_TIMEOUT");
            result.setErrorMessage("FastAPI request timed out");
            return result;
        }
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_CONNECTION_FAILED);
        result.setErrorCode("FASTAPI_CONNECTION_FAILED");
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

    private LocalDateTime readAcceptedAt(String body, LocalDateTime fallback) {
        if (body == null || body.isBlank()) {
            return fallback;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode acceptedAt = root.get("accepted_at");
            if (acceptedAt == null || acceptedAt.isNull()) {
                acceptedAt = root.get("acceptedAt");
            }
            if (acceptedAt == null || acceptedAt.isNull() || acceptedAt.asText().isBlank()) {
                return fallback;
            }
            LocalDateTime parsed = parseDateTime(acceptedAt.asText());
            if (parsed == null) {
                return fallback;
            }
            return parsed;
        } catch (Exception e) {
            log.warn("FastAPI response body could not be parsed");
            return fallback;
        }
    }

    private LocalDateTime parseDateTime(String raw) {
        try {
            return OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        return null;
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
