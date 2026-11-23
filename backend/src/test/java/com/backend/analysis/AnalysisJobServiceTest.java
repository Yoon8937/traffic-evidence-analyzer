package com.backend.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisJobServiceTest {

    private static final String OBJECT_KEY = "videos/key.MOV";

    @Mock
    private AnalysisJobRepository analysisJobRepository;
    @Mock
    private FastApiAnalysisClient fastApiAnalysisClient;

    private AnalysisJobService analysisJobService;

    @BeforeEach
    void setUp() {
        analysisJobService = new AnalysisJobService(analysisJobRepository, fastApiAnalysisClient);
        lenient().when(analysisJobRepository.save(any(AnalysisJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void dispatch_accepted_savesAcceptedAtAndStaysPending() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), OBJECT_KEY))
                .thenReturn(accepted(LocalDateTime.of(2026, 11, 19, 4, 20)));

        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);

        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 19, 4, 20), job.getAcceptedAt());
        assertNull(job.getErrorCode());
        assertNull(job.getStartedAt());
        assertNull(job.getFinishedAt());
        assertEquals(Integer.valueOf(0), job.getRetryCount());
    }

    @Test
    void dispatch_processing_doesNotOverwriteAcceptedAt() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        LocalDateTime existing = LocalDateTime.of(2026, 11, 19, 4, 20);
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), OBJECT_KEY))
                .thenReturn(accepted(existing));

        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);
        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);

        assertEquals(existing, job.getAcceptedAt());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        verify(fastApiAnalysisClient, times(1)).submit(job.getId(), job.getVideoId(), OBJECT_KEY);
    }

    @Test
    void dispatch_processingWithoutPriorAccept_doesNotSaveResponseAcceptedAt() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiAcceptanceResult result = remoteExists("PROCESSING");
        result.setAcceptedAt(LocalDateTime.of(2026, 11, 20, 1, 0));
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), OBJECT_KEY)).thenReturn(result);

        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);
        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);

        assertNull(job.getAcceptedAt());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED, job.getErrorCode());
        assertNull(job.getStartedAt());
        assertNull(job.getFinishedAt());
        verify(fastApiAnalysisClient, times(1)).submit(job.getId(), job.getVideoId(), OBJECT_KEY);
    }

    @Test
    void dispatch_completed_doesNotInventResult() {
        assertRemoteTerminalDoesNotInventResult("COMPLETED");
    }

    @Test
    void dispatch_failed_doesNotInventResult() {
        assertRemoteTerminalDoesNotInventResult("FAILED");
    }

    @Test
    void dispatch_queueFull_retriesSameJobIdAfterDelay() {
        assertRetryKeepsJobId(retryable(
                FastApiAcceptanceResult.OUTCOME_SERVER_ERROR,
                FastApiAcceptanceResult.ERROR_QUEUE_FULL,
                "Too many in-progress jobs"
        ));
    }

    @Test
    void dispatch_workerUnavailable_retriesSameJobIdAfterDelay() {
        assertRetryKeepsJobId(retryable(
                FastApiAcceptanceResult.OUTCOME_SERVER_ERROR,
                FastApiAcceptanceResult.ERROR_WORKER_UNAVAILABLE,
                "Analysis worker unavailable"
        ));
    }

    @Test
    void dispatch_retryCountIncrementsOnEachResendAndCapFails() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), OBJECT_KEY))
                .thenReturn(retryable(
                        FastApiAcceptanceResult.OUTCOME_SERVER_ERROR,
                        FastApiAcceptanceResult.ERROR_QUEUE_FULL,
                        "Too many in-progress jobs"
                ));

        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);
        assertEquals(Integer.valueOf(0), job.getRetryCount());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());

        job.setUpdatedAt(LocalDateTime.now());
        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);
        assertEquals(Integer.valueOf(0), job.getRetryCount());

        job.setUpdatedAt(LocalDateTime.now().minusSeconds(2));
        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);
        assertEquals(Integer.valueOf(1), job.getRetryCount());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertNull(job.getAcceptedAt());

        job.setUpdatedAt(LocalDateTime.now().minusSeconds(3));
        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);

        assertEquals(Integer.valueOf(2), job.getRetryCount());
        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
        assertEquals(AnalysisJob.ERROR_DISPATCH_RETRY_EXHAUSTED, job.getErrorCode());
        assertNull(job.getAcceptedAt());
        verify(fastApiAnalysisClient, times(3)).submit(job.getId(), job.getVideoId(), OBJECT_KEY);
    }

    @Test
    void dispatch_clientErrorsAreNotRetried() {
        AnalysisJob badRequest = AnalysisJob.pendingJob(UUID.randomUUID());
        when(fastApiAnalysisClient.submit(badRequest.getId(), badRequest.getVideoId(), OBJECT_KEY))
                .thenReturn(clientError(400));
        analysisJobService.dispatchToFastApi(badRequest, OBJECT_KEY);
        analysisJobService.dispatchToFastApi(badRequest, OBJECT_KEY);

        AnalysisJob unauthorized = AnalysisJob.pendingJob(UUID.randomUUID());
        when(fastApiAnalysisClient.submit(unauthorized.getId(), unauthorized.getVideoId(), OBJECT_KEY))
                .thenReturn(clientError(401));
        analysisJobService.dispatchToFastApi(unauthorized, OBJECT_KEY);
        analysisJobService.dispatchToFastApi(unauthorized, OBJECT_KEY);

        assertEquals(AnalysisJob.STATUS_FAILED, badRequest.getStatus());
        assertEquals(AnalysisJob.STATUS_FAILED, unauthorized.getStatus());
        assertEquals(Integer.valueOf(0), badRequest.getRetryCount());
        assertEquals(Integer.valueOf(0), unauthorized.getRetryCount());
        assertNull(badRequest.getAcceptedAt());
        verify(fastApiAnalysisClient, times(1)).submit(badRequest.getId(), badRequest.getVideoId(), OBJECT_KEY);
        verify(fastApiAnalysisClient, times(1)).submit(unauthorized.getId(), unauthorized.getVideoId(), OBJECT_KEY);
    }

    @Test
    void dispatch_conflictIsNotAcceptance() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiAcceptanceResult result = clientError(409);
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), OBJECT_KEY)).thenReturn(result);

        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);

        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
        assertNull(job.getAcceptedAt());
        assertEquals(FastApiAcceptanceResult.ERROR_CLIENT, job.getErrorCode());
    }

    @Test
    void dispatch_serverTimeoutAndConnectionFailureAreRetried() {
        assertRetryKeepsJobId(retryable(
                FastApiAcceptanceResult.OUTCOME_SERVER_ERROR,
                FastApiAcceptanceResult.ERROR_SERVER,
                "FastAPI returned HTTP 500"
        ));
        assertRetryKeepsJobId(retryable(
                FastApiAcceptanceResult.OUTCOME_TIMEOUT,
                FastApiAcceptanceResult.ERROR_TIMEOUT,
                "FastAPI request timed out"
        ));
        assertRetryKeepsJobId(retryable(
                FastApiAcceptanceResult.OUTCOME_CONNECTION_FAILED,
                FastApiAcceptanceResult.ERROR_CONNECTION,
                "FastAPI connection failed"
        ));
    }

    @Test
    void dispatch_retryDoesNotOverwriteAcceptedAt() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        LocalDateTime acceptedAt = LocalDateTime.of(2026, 11, 19, 4, 20);
        job.setAcceptedAt(acceptedAt);
        job.setErrorCode(FastApiAcceptanceResult.ERROR_QUEUE_FULL);
        job.setRetryCount(1);
        job.setUpdatedAt(LocalDateTime.now().minusSeconds(5));

        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);

        assertEquals(acceptedAt, job.getAcceptedAt());
        assertEquals(Integer.valueOf(1), job.getRetryCount());
        verify(fastApiAnalysisClient, never()).submit(any(), any(), any());
    }

    @Test
    void findJobsDueForRetry_usesSavedColumnsAndDelays() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 23, 10, 0, 0);
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        when(analysisJobRepository.findPendingRetriesDue(
                any(),
                anyInt(),
                any(),
                any(),
                any()
        )).thenReturn(List.of(job));

        List<AnalysisJob> due = analysisJobService.findJobsDueForRetry(now);

        assertEquals(1, due.size());
        assertSame(job, due.get(0));
        verify(analysisJobRepository).findPendingRetriesDue(
                eq(AnalysisJob.STATUS_PENDING),
                eq(AnalysisJob.MAX_DISPATCH_RESENDS),
                eq(AnalysisJobService.RETRYABLE_ERROR_CODES),
                eq(now.minusSeconds(1)),
                eq(now.minusSeconds(2))
        );
    }

    @Test
    void check_completed_doesNotMarkAnalysisJobCompleted() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setErrorCode(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED);
        FastApiJobStatusResponse response = remoteStatus("COMPLETED");
        response.setFinishedAt(LocalDateTime.of(2026, 11, 19, 5, 0));
        response.setIsValid(Boolean.TRUE);
        FastApiJobSummary summary = new FastApiJobSummary();
        summary.setFrames(4L);
        response.setSummary(summary);
        response.setEvents(java.util.List.of(new FastApiJobEvent()));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        FastApiJobStatusResponse returned = analysisJobService.checkFastApiJobStatus(job);

        assertSame(response, returned);
        assertEquals(4L, returned.getSummary().getFrames());
        assertEquals(1, returned.getEvents().size());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED, job.getErrorCode());
        assertNull(job.getFinishedAt());
        verify(fastApiAnalysisClient, never()).submit(any(), any(), any());
    }

    @Test
    void check_failed_marksSpringFailed() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setErrorCode(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED);
        FastApiJobStatusResponse response = remoteStatus("FAILED");
        response.setErrorCode("ANALYSIS_FAILED");
        response.setErrorMessage("detector crashed");
        response.setFinishedAt(LocalDateTime.of(2026, 11, 19, 6, 0));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        analysisJobService.checkFastApiJobStatus(job);

        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
        assertEquals("ANALYSIS_FAILED", job.getErrorCode());
        assertEquals("detector crashed", job.getErrorMessage());
        assertEquals(LocalDateTime.of(2026, 11, 19, 6, 0), job.getFinishedAt());
    }

    @Test
    void check_failed_doesNotRegressCompletedJob() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        job.setErrorCode("ALREADY_DONE");
        job.setFinishedAt(LocalDateTime.of(2026, 11, 18, 1, 0));
        FastApiJobStatusResponse response = remoteStatus("FAILED");
        response.setErrorCode("ANALYSIS_FAILED");
        response.setErrorMessage("late failure");
        response.setFinishedAt(LocalDateTime.of(2026, 11, 19, 6, 0));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        analysisJobService.recoverJobStatus(job);

        assertEquals(AnalysisJob.STATUS_COMPLETED, job.getStatus());
        assertEquals("ALREADY_DONE", job.getErrorCode());
        assertEquals(LocalDateTime.of(2026, 11, 18, 1, 0), job.getFinishedAt());
    }

    @Test
    void check_processing_doesNotChangeStartedAtOrStatus() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        LocalDateTime startedAt = LocalDateTime.of(2026, 11, 19, 3, 0);
        job.setStartedAt(startedAt);
        job.setErrorCode(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED);
        FastApiJobStatusResponse response = remoteStatus("PROCESSING");
        response.setStartedAt(LocalDateTime.of(2026, 11, 19, 4, 20));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        FastApiJobStatusResponse returned = analysisJobService.checkFastApiJobStatus(job);

        assertEquals("PROCESSING", returned.getStatus());
        assertEquals(startedAt, job.getStartedAt());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED, job.getErrorCode());
    }

    @Test
    void check_notFound_doesNotPost() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setOutcome(FastApiJobStatusResponse.OUTCOME_NOT_FOUND);
        response.setHttpStatus(404);
        response.setErrorCode("INVALID_REQUEST");
        response.setErrorMessage("Job not found");
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        analysisJobService.checkFastApiJobStatus(job);

        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED, job.getErrorCode());
        assertEquals("GET recovery is still needed", job.getErrorMessage());
        verify(fastApiAnalysisClient, never()).submit(any(), any(), any());
    }

    @Test
    void recoverJobStatus_checksRecoveryNotImplementedWithGet() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setErrorCode(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED);
        when(analysisJobRepository.findJobsNeedingStatusRecovery(any(), any())).thenReturn(List.of(job));
        FastApiJobStatusResponse response = remoteStatus("PROCESSING");
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        java.util.List<FastApiJobStatusResponse> results = analysisJobService.recoverJobStatus();

        assertEquals(1, results.size());
        assertSame(response, results.get(0));
        assertTrue(AnalysisJobService.STATUS_RECOVERY_ERROR_CODES.contains(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED));
        assertTrue(AnalysisJobService.STATUS_RECOVERY_ERROR_CODES.contains(FastApiAcceptanceResult.ERROR_RESPONSE_INVALID));
        verify(analysisJobRepository).findJobsNeedingStatusRecovery(
                eq(AnalysisJob.STATUS_PENDING),
                eq(AnalysisJobService.STATUS_RECOVERY_ERROR_CODES)
        );
        verify(fastApiAnalysisClient).getJobStatus(job.getId());
        verify(fastApiAnalysisClient, never()).submit(any(), any(), any());
        assertEquals(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED, job.getErrorCode());
    }

    @Test
    void check_accepted_clearsRecoveryMarkerWhenAcceptedAtIsUsable() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setErrorCode(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED);
        FastApiJobStatusResponse response = remoteStatus("ACCEPTED");
        response.setAcceptedAt(LocalDateTime.of(2026, 11, 19, 4, 20));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        analysisJobService.checkFastApiJobStatus(job);

        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 19, 4, 20), job.getAcceptedAt());
        assertNull(job.getErrorCode());
        assertNull(job.getStartedAt());
    }

    @Test
    void check_acceptedWithoutTime_keepsRecoveryMarker() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setErrorCode(FastApiAcceptanceResult.ERROR_RESPONSE_INVALID);
        FastApiJobStatusResponse response = remoteStatus("ACCEPTED");
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        analysisJobService.checkFastApiJobStatus(job);

        assertNull(job.getAcceptedAt());
        assertEquals(FastApiAcceptanceResult.ERROR_RESPONSE_INVALID, job.getErrorCode());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
    }

    @Test
    void check_transportFailure_doesNotChangeJob() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setErrorCode(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED);
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setOutcome(FastApiJobStatusResponse.OUTCOME_SERVER_ERROR);
        response.setHttpStatus(500);
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        analysisJobService.checkFastApiJobStatus(job);

        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED, job.getErrorCode());
        verify(analysisJobRepository, never()).save(any());
    }

    private FastApiJobStatusResponse remoteStatus(String status) {
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setOutcome(FastApiJobStatusResponse.OUTCOME_REMOTE);
        response.setHttpStatus(200);
        response.setStatus(status);
        return response;
    }

    private void assertRemoteTerminalDoesNotInventResult(String remoteStatus) {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiAcceptanceResult result = remoteExists(remoteStatus);
        result.setAcceptedAt(LocalDateTime.of(2026, 11, 20, 1, 0));
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), OBJECT_KEY)).thenReturn(result);

        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);

        assertNull(job.getAcceptedAt());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED, job.getErrorCode());
        assertNull(job.getStartedAt());
        assertNull(job.getFinishedAt());
        verify(fastApiAnalysisClient, times(1)).submit(job.getId(), job.getVideoId(), OBJECT_KEY);
    }

    private void assertRetryKeepsJobId(FastApiAcceptanceResult failure) {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), OBJECT_KEY)).thenReturn(failure);

        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals(failure.getErrorCode(), job.getErrorCode());
        assertEquals(Integer.valueOf(0), job.getRetryCount());
        assertNull(job.getAcceptedAt());

        job.setUpdatedAt(LocalDateTime.now().minusSeconds(2));
        analysisJobService.dispatchToFastApi(job, OBJECT_KEY);

        assertEquals(Integer.valueOf(1), job.getRetryCount());
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertNull(job.getAcceptedAt());
        verify(fastApiAnalysisClient, times(2)).submit(job.getId(), job.getVideoId(), OBJECT_KEY);
    }

    private FastApiAcceptanceResult accepted(LocalDateTime acceptedAt) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_ACCEPTED);
        result.setHttpStatus(202);
        result.setRemoteStatus("ACCEPTED");
        result.setAcceptedAt(acceptedAt);
        return result;
    }

    private FastApiAcceptanceResult remoteExists(String remoteStatus) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_REMOTE_EXISTS);
        result.setHttpStatus(202);
        result.setRemoteStatus(remoteStatus);
        return result;
    }

    private FastApiAcceptanceResult retryable(String outcome, String errorCode, String message) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(outcome);
        result.setHttpStatus(503);
        result.setErrorCode(errorCode);
        result.setErrorMessage(message);
        return result;
    }

    private FastApiAcceptanceResult clientError(int status) {
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR);
        result.setHttpStatus(status);
        result.setErrorCode(FastApiAcceptanceResult.ERROR_CLIENT);
        result.setErrorMessage("FastAPI returned HTTP " + status);
        return result;
    }
}
