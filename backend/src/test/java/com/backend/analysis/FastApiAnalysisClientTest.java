package com.backend.analysis;

import com.backend.config.TraceIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class FastApiAnalysisClientTest {

    private static final String TOKEN = "test-internal-token";
    private static final String TRACE_ID = "trace-test-1";

    private MockRestServiceServer server;
    private FastApiAnalysisClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new FastApiAnalysisClient(restTemplate, JsonMapper.builder().build(), "http://localhost:8000", TOKEN);
        MDC.put(TraceIdFilter.TRACE_ID, TRACE_ID);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void submit_accepted202_readsContractFieldsAndHeaders() {
        UUID jobId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID videoId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        String objectKey = "videos/2026/09/28/" + videoId + ".MOV";

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(header(TraceIdFilter.HEADER_TRACE_ID, TRACE_ID))
                .andExpect(header(FastApiAnalysisClient.HEADER_INTERNAL_TOKEN, TOKEN))
                .andExpect(content().json(
                        "{\"job_id\":\"550e8400-e29b-41d4-a716-446655440000\","
                                + "\"video_id\":\"11111111-1111-1111-1111-111111111111\","
                                + "\"object_key\":\"" + objectKey + "\"}"
                ))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"job_id\":\"550e8400-e29b-41d4-a716-446655440000\","
                                + "\"status\":\"ACCEPTED\","
                                + "\"accepted_at\":\"2026-11-19T04:20:00Z\"}"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, objectKey);

        server.verify();
        assertTrue(result.isAccepted());
        assertEquals(202, result.getHttpStatus());
        assertEquals("ACCEPTED", result.getRemoteStatus());
        assertEquals(LocalDateTime.of(2026, 11, 19, 4, 20), result.getAcceptedAt());
        assertNull(result.getErrorCode());
    }

    @Test
    void submit_http202Processing_isExistingJob() {
        FastApiAcceptanceResult result = postStatus(HttpStatus.ACCEPTED, "PROCESSING");

        assertTrue(result.isRemoteExists());
        assertFalse(result.isAccepted());
        assertEquals("PROCESSING", result.getRemoteStatus());
        assertNull(result.getAcceptedAt());
    }

    @Test
    void submit_http202Completed_isExistingJob() {
        FastApiAcceptanceResult result = postStatus(HttpStatus.ACCEPTED, "COMPLETED");

        assertTrue(result.isRemoteExists());
        assertFalse(result.isAccepted());
        assertEquals("COMPLETED", result.getRemoteStatus());
        assertNull(result.getAcceptedAt());
    }

    @Test
    void submit_http202Failed_isExistingJob() {
        FastApiAcceptanceResult result = postStatus(HttpStatus.ACCEPTED, "FAILED");

        assertTrue(result.isRemoteExists());
        assertFalse(result.isAccepted());
        assertEquals("FAILED", result.getRemoteStatus());
        assertNull(result.getAcceptedAt());
    }

    @Test
    void submit_http503QueueFull_isRetryableWithSameJobId() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"job_id\":\"" + jobId + "\"}"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error_code\":\"QUEUE_FULL\",\"message\":\"Too many in-progress jobs\"}"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/key.MOV");

        assertTrue(result.isRetryable());
        assertEquals(FastApiAcceptanceResult.ERROR_QUEUE_FULL, result.getErrorCode());
        assertEquals("Too many in-progress jobs", result.getErrorMessage());
        assertNull(result.getAcceptedAt());
    }

    @Test
    void submit_http503WorkerUnavailable_isRetryable() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error_code\":\"WORKER_UNAVAILABLE\",\"message\":\"Analysis worker unavailable\"}"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/key.MOV");

        assertTrue(result.isRetryable());
        assertEquals(FastApiAcceptanceResult.ERROR_WORKER_UNAVAILABLE, result.getErrorCode());
        assertEquals("Analysis worker unavailable", result.getErrorMessage());
    }

    @Test
    void submit_http409_isNotAcceptance() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error_code\":\"QUEUE_FULL\"}"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/key.MOV");

        assertFalse(result.isAccepted());
        assertFalse(result.isRetryable());
        assertEquals(FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR, result.getOutcome());
        assertEquals(FastApiAcceptanceResult.ERROR_CLIENT, result.getErrorCode());
        assertNull(result.getAcceptedAt());
    }

    @Test
    void submit_http400And401_areNotRetried() {
        assertClientError(HttpStatus.BAD_REQUEST, "{\"error_code\":\"QUEUE_FULL\",\"message\":\"Too many in-progress jobs\"}");
        assertClientError(HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    @Test
    void submit_http500TextPlain_doesNotRequireJson() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("internal failure {not-json}"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/key.MOV");

        assertTrue(result.isRetryable());
        assertEquals(FastApiAcceptanceResult.OUTCOME_SERVER_ERROR, result.getOutcome());
        assertEquals(FastApiAcceptanceResult.ERROR_SERVER, result.getErrorCode());
        assertEquals("FastAPI returned HTTP 500", result.getErrorMessage());
    }

    @Test
    void submit_http500JsonQueueFull_doesNotInferStatusFromErrorCode() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error_code\":\"QUEUE_FULL\"}"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/key.MOV");

        assertEquals(FastApiAcceptanceResult.ERROR_SERVER, result.getErrorCode());
        assertTrue(result.isRetryable());
    }

    @Test
    void getJobStatus_accepted_parsesUtcAcceptedAt() {
        UUID jobId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(TraceIdFilter.HEADER_TRACE_ID, TRACE_ID))
                .andExpect(header(FastApiAnalysisClient.HEADER_INTERNAL_TOKEN, TOKEN))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"ACCEPTED\",\"accepted_at\":\"2026-11-19T04:20:00Z\"}"));

        FastApiJobStatusResponse result = client.getJobStatus(jobId);

        server.verify();
        assertEquals(FastApiJobStatusResponse.OUTCOME_REMOTE, result.getOutcome());
        assertEquals(jobId.toString(), result.getJobId());
        assertEquals("ACCEPTED", result.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 19, 4, 20), result.getAcceptedAt());
        assertNull(result.getStartedAt());
    }

    @Test
    void getJobStatus_processing_parsesOffsetZeroStartedAt() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"job_id\":\"" + jobId + "\",\"status\":\"PROCESSING\","
                                + "\"started_at\":\"2026-11-19T04:20:00+00:00\"}"));

        FastApiJobStatusResponse result = client.getJobStatus(jobId);

        assertEquals("PROCESSING", result.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 19, 4, 20), result.getStartedAt());
        assertNull(result.getAcceptedAt());
    }

    @Test
    void getJobStatus_completed_parsesFullResult() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"COMPLETED\",\"finished_at\":\"2026-11-19T05:00:00Z\","
                                + "\"is_valid\":false,\"summary\":{\"frames\":12,\"track_count\":3,"
                                + "\"crossed_count\":2,\"violation_count\":1,\"fps\":29.97,\"processing_ms\":840},"
                                + "\"events\":["
                                + "{\"event_type\":\"line_cross\",\"frame\":4,\"track_id\":9,"
                                + "\"evidence_s3_key\":\"evidence/a.jpg\"},"
                                + "{\"event_type\":\"stop\",\"frame\":8,\"track_id\":9,\"evidence_s3_key\":null}"
                                + "],\"result_video_s3_key\":\"results/video.mp4\",\"callback_delivered\":false}"));

        FastApiJobStatusResponse result = client.getJobStatus(jobId);

        assertEquals("COMPLETED", result.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 19, 5, 0), result.getFinishedAt());
        assertEquals(Boolean.FALSE, result.getIsValid());
        assertEquals(Long.valueOf(12), result.getSummary().getFrames());
        assertEquals(Long.valueOf(3), result.getSummary().getTrackCount());
        assertEquals(Long.valueOf(2), result.getSummary().getCrossedCount());
        assertEquals(Long.valueOf(1), result.getSummary().getViolationCount());
        assertEquals(29.97d, result.getSummary().getFps());
        assertEquals(Long.valueOf(840), result.getSummary().getProcessingMs());
        assertEquals(2, result.getEvents().size());
        assertEquals("line_cross", result.getEvents().get(0).getEventType());
        assertEquals(Long.valueOf(4), result.getEvents().get(0).getFrame());
        assertEquals(Long.valueOf(9), result.getEvents().get(0).getTrackId());
        assertEquals("evidence/a.jpg", result.getEvents().get(0).getEvidenceS3Key());
        assertNull(result.getEvents().get(1).getEvidenceS3Key());
        assertEquals("results/video.mp4", result.getResultVideoS3Key());
        assertEquals(Boolean.FALSE, result.getCallbackDelivered());
        assertEquals(jobId.toString(), result.getJobId());
    }

    @Test
    void getJobStatus_failed_parsesError() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"FAILED\",\"error_code\":\"ANALYSIS_FAILED\","
                                + "\"error_message\":\"detector crashed\","
                                + "\"finished_at\":\"2026-11-19T06:00:00Z\"}"));

        FastApiJobStatusResponse result = client.getJobStatus(jobId);

        assertEquals("FAILED", result.getStatus());
        assertEquals("ANALYSIS_FAILED", result.getErrorCode());
        assertEquals("detector crashed", result.getErrorMessage());
        assertEquals(LocalDateTime.of(2026, 11, 19, 6, 0), result.getFinishedAt());
        assertNull(result.getCallbackDelivered());
    }

    @Test
    void getJobStatus_notFound_isDistinctFromTransportFailure() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error_code\":\"INVALID_REQUEST\",\"message\":\"Job not found\"}"));

        FastApiJobStatusResponse result = client.getJobStatus(jobId);

        assertEquals(FastApiJobStatusResponse.OUTCOME_NOT_FOUND, result.getOutcome());
        assertEquals(404, result.getHttpStatus());
        assertEquals("INVALID_REQUEST", result.getErrorCode());
        assertEquals("Job not found", result.getErrorMessage());
        assertEquals(jobId.toString(), result.getJobId());
    }

    @Test
    void getJobStatus_unauthorized() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("unauthorized"));

        FastApiJobStatusResponse result = client.getJobStatus(jobId);

        assertEquals(FastApiJobStatusResponse.OUTCOME_UNAUTHORIZED, result.getOutcome());
        assertEquals(401, result.getHttpStatus());
    }

    @Test
    void getJobStatus_serverErrorTextPlain() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("internal failure {not-json}"));

        FastApiJobStatusResponse result = client.getJobStatus(jobId);

        assertEquals(FastApiJobStatusResponse.OUTCOME_SERVER_ERROR, result.getOutcome());
        assertEquals(500, result.getHttpStatus());
        assertNull(result.getStatus());
    }

    @Test
    void getJobStatus_completed_keepsNullAndOptionalFields() {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs/" + jobId))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"COMPLETED\",\"finished_at\":null,\"is_valid\":null,"
                                + "\"summary\":{\"frames\":null,\"track_count\":1,\"crossed_count\":null,"
                                + "\"violation_count\":null,\"fps\":null,\"processing_ms\":null},"
                                + "\"events\":[{\"event_type\":\"line_cross\",\"frame\":null,\"track_id\":3,"
                                + "\"evidence_s3_key\":null}],\"result_video_s3_key\":null}"));

        FastApiJobStatusResponse result = client.getJobStatus(jobId);

        assertEquals("COMPLETED", result.getStatus());
        assertNull(result.getFinishedAt());
        assertNull(result.getIsValid());
        assertNull(result.getSummary().getFrames());
        assertEquals(Long.valueOf(1), result.getSummary().getTrackCount());
        assertNull(result.getSummary().getCrossedCount());
        assertNull(result.getSummary().getViolationCount());
        assertNull(result.getSummary().getFps());
        assertNull(result.getSummary().getProcessingMs());
        assertEquals(1, result.getEvents().size());
        assertNull(result.getEvents().get(0).getFrame());
        assertEquals(Long.valueOf(3), result.getEvents().get(0).getTrackId());
        assertNull(result.getEvents().get(0).getEvidenceS3Key());
        assertNull(result.getResultVideoS3Key());
        assertNull(result.getCallbackDelivered());
    }

    private FastApiAcceptanceResult postStatus(HttpStatus httpStatus, String remoteStatus) {
        UUID jobId = UUID.randomUUID();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(httpStatus)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"job_id\":\"" + jobId + "\",\"status\":\"" + remoteStatus
                                + "\",\"accepted_at\":\"2026-11-19T01:02:03Z\"}"));
        return client.submit(jobId, UUID.randomUUID(), "videos/key.MOV");
    }

    private void assertClientError(HttpStatus status, String body) {
        server.reset();
        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status).body(body));

        FastApiAcceptanceResult result = client.submit(UUID.randomUUID(), UUID.randomUUID(), "videos/key.MOV");

        assertFalse(result.isRetryable());
        assertFalse(result.isAccepted());
        assertEquals(FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR, result.getOutcome());
        assertEquals(FastApiAcceptanceResult.ERROR_CLIENT, result.getErrorCode());
        assertNull(result.getAcceptedAt());
    }
}
