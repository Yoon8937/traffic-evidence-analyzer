package com.backend.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisJobServiceTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;
    @Mock
    private FastApiAnalysisClient fastApiAnalysisClient;

    private AnalysisJobService analysisJobService;

    @BeforeEach
    void setUp() {
        analysisJobService = new AnalysisJobService(analysisJobRepository, fastApiAnalysisClient);
        when(analysisJobRepository.save(any(AnalysisJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void dispatch_accepted_updatesAcceptedAtAndKeepsPending() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_ACCEPTED);
        result.setAcceptedAt(LocalDateTime.of(2026, 11, 19, 4, 20));
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), "videos/key.MOV")).thenReturn(result);

        analysisJobService.dispatchToFastApi(job, "videos/key.MOV");

        ArgumentCaptor<AnalysisJob> captor = ArgumentCaptor.forClass(AnalysisJob.class);
        verify(analysisJobRepository).save(captor.capture());
        AnalysisJob saved = captor.getValue();
        assertEquals(AnalysisJob.STATUS_PENDING, saved.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 19, 4, 20), saved.getAcceptedAt());
        assertNull(saved.getErrorCode());
        assertEquals(Integer.valueOf(0), saved.getRetryCount());
    }

    @Test
    void dispatch_clientError_marksFailed() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR);
        result.setErrorCode("FASTAPI_CLIENT_ERROR");
        result.setErrorMessage("FastAPI returned HTTP 400");
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), "videos/key.MOV")).thenReturn(result);

        analysisJobService.dispatchToFastApi(job, "videos/key.MOV");

        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
        assertEquals("FASTAPI_CLIENT_ERROR", job.getErrorCode());
        assertNull(job.getAcceptedAt());
        assertEquals(Integer.valueOf(0), job.getRetryCount());
    }

    @Test
    void dispatch_timeout_keepsPendingAndRecordsError() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_TIMEOUT);
        result.setErrorCode("FASTAPI_TIMEOUT");
        result.setErrorMessage("FastAPI request timed out");
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), "videos/key.MOV")).thenReturn(result);

        analysisJobService.dispatchToFastApi(job, "videos/key.MOV");

        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals("FASTAPI_TIMEOUT", job.getErrorCode());
        assertEquals(Integer.valueOf(0), job.getRetryCount());
    }

    @Test
    void dispatch_connectionFailed_keepsPendingAndRecordsError() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_CONNECTION_FAILED);
        result.setErrorCode("FASTAPI_CONNECTION_FAILED");
        result.setErrorMessage("FastAPI connection failed");
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), "videos/key.MOV")).thenReturn(result);

        analysisJobService.dispatchToFastApi(job, "videos/key.MOV");

        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals("FASTAPI_CONNECTION_FAILED", job.getErrorCode());
    }

    @Test
    void dispatch_serverError_keepsPendingAndRecordsError() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        FastApiAcceptanceResult result = new FastApiAcceptanceResult();
        result.setOutcome(FastApiAcceptanceResult.OUTCOME_SERVER_ERROR);
        result.setErrorCode("FASTAPI_SERVER_ERROR");
        result.setErrorMessage("FastAPI returned HTTP 500");
        when(fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), "videos/key.MOV")).thenReturn(result);

        analysisJobService.dispatchToFastApi(job, "videos/key.MOV");

        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        assertEquals("FASTAPI_SERVER_ERROR", job.getErrorCode());
    }
}
