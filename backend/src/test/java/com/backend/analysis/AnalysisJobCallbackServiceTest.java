package com.backend.analysis;

import com.backend.analysis.dto.AnalysisJobStatusCallbackRequest;
import com.backend.exception.CallbackValidationException;
import com.backend.exception.JobNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisJobCallbackServiceTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    private AnalysisJobCallbackService service;

    @BeforeEach
    void setUp() {
        service = new AnalysisJobCallbackService(analysisJobRepository);
        lenient().when(analysisJobRepository.save(any(AnalysisJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void pendingToProcessing() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.handleStatus(job.getId(), processingRequest(job.getId(), "2026-11-24T04:00:00Z"));

        assertEquals(AnalysisJob.STATUS_PROCESSING, job.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 0), job.getStartedAt());
    }

    @Test
    void processingParsesPlusOffset() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.handleStatus(job.getId(), processingRequest(job.getId(), "2026-11-24T04:00:00+00:00"));

        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 0), job.getStartedAt());
    }

    @Test
    void duplicateProcessingKeepsStartedAt() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_PROCESSING);
        job.setStartedAt(LocalDateTime.of(2026, 11, 24, 4, 0));
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.handleStatus(job.getId(), processingRequest(job.getId(), "2026-11-24T05:00:00Z"));

        assertEquals(AnalysisJob.STATUS_PROCESSING, job.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 0), job.getStartedAt());
        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
    }

    @Test
    void completedIgnoresProcessing() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.handleStatus(job.getId(), processingRequest(job.getId(), "2026-11-24T04:00:00Z"));

        assertEquals(AnalysisJob.STATUS_COMPLETED, job.getStatus());
        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
    }

    @Test
    void failedIgnoresProcessing() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_FAILED);
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.handleStatus(job.getId(), processingRequest(job.getId(), "2026-11-24T04:00:00Z"));

        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
    }

    @Test
    void pendingToFailed() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.handleStatus(job.getId(), failedRequest(job.getId()));

        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
        assertEquals("ANALYSIS_FAILED", job.getErrorCode());
        assertEquals("Video processing failed", job.getErrorMessage());
        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 5), job.getFinishedAt());
    }

    @Test
    void processingToFailed() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_PROCESSING);
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.handleStatus(job.getId(), failedRequest(job.getId()));

        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
    }

    @Test
    void duplicateFailedDoesNotNullExistingFields() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_FAILED);
        job.setErrorCode("ANALYSIS_FAILED");
        job.setErrorMessage("Video processing failed");
        job.setFinishedAt(LocalDateTime.of(2026, 11, 24, 4, 5));
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        AnalysisJobStatusCallbackRequest request = new AnalysisJobStatusCallbackRequest();
        request.setJobId(job.getId());
        request.setStatus(AnalysisJob.STATUS_FAILED);

        service.handleStatus(job.getId(), request);

        assertEquals("ANALYSIS_FAILED", job.getErrorCode());
        assertEquals("Video processing failed", job.getErrorMessage());
        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 5), job.getFinishedAt());
    }

    @Test
    void completedIgnoresFailed() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.handleStatus(job.getId(), failedRequest(job.getId()));

        assertEquals(AnalysisJob.STATUS_COMPLETED, job.getStatus());
        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
    }

    @Test
    void unknownJob() {
        UUID jobId = UUID.randomUUID();
        when(analysisJobRepository.findById(jobId)).thenReturn(Optional.empty());
        assertThrows(JobNotFoundException.class, () -> service.handleStatus(jobId, processingRequest(jobId, "2026-11-24T04:00:00Z")));
    }

    @Test
    void jobIdMismatch() {
        AnalysisJob job = pendingJob();
        assertThrows(CallbackValidationException.class,
                () -> service.handleStatus(job.getId(), processingRequest(UUID.randomUUID(), "2026-11-24T04:00:00Z")));
        verify(analysisJobRepository, never()).findById(any());
    }

    @Test
    void missingStatus() {
        AnalysisJob job = pendingJob();
        AnalysisJobStatusCallbackRequest request = new AnalysisJobStatusCallbackRequest();
        request.setJobId(job.getId());
        assertThrows(CallbackValidationException.class, () -> service.handleStatus(job.getId(), request));
    }

    @Test
    void unsupportedStatus() {
        AnalysisJob job = pendingJob();
        AnalysisJobStatusCallbackRequest request = new AnalysisJobStatusCallbackRequest();
        request.setJobId(job.getId());
        request.setStatus(AnalysisJob.STATUS_COMPLETED);
        assertThrows(CallbackValidationException.class, () -> service.handleStatus(job.getId(), request));
    }

    @Test
    void invalidStartedAt() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        assertThrows(CallbackValidationException.class,
                () -> service.handleStatus(job.getId(), processingRequest(job.getId(), "not-a-date")));
    }

    @Test
    void saveFailurePropagates() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findById(job.getId())).thenReturn(Optional.of(job));
        when(analysisJobRepository.save(any(AnalysisJob.class))).thenThrow(new DataIntegrityViolationException("db"));
        assertThrows(DataIntegrityViolationException.class,
                () -> service.handleStatus(job.getId(), processingRequest(job.getId(), "2026-11-24T04:00:00Z")));
    }

    private AnalysisJob pendingJob() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        return job;
    }

    private AnalysisJobStatusCallbackRequest processingRequest(UUID jobId, String startedAt) {
        AnalysisJobStatusCallbackRequest request = new AnalysisJobStatusCallbackRequest();
        request.setJobId(jobId);
        request.setStatus(AnalysisJob.STATUS_PROCESSING);
        request.setStartedAt(startedAt);
        return request;
    }

    private AnalysisJobStatusCallbackRequest failedRequest(UUID jobId) {
        AnalysisJobStatusCallbackRequest request = new AnalysisJobStatusCallbackRequest();
        request.setJobId(jobId);
        request.setStatus(AnalysisJob.STATUS_FAILED);
        request.setErrorCode("ANALYSIS_FAILED");
        request.setErrorMessage("Video processing failed");
        request.setFinishedAt("2026-11-24T04:05:00Z");
        return request;
    }
}
