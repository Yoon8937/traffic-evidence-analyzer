package com.backend.analysis;

import com.backend.exception.CallbackConflictException;
import com.backend.exception.CallbackValidationException;
import com.backend.exception.JobNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisResultCallbackServiceTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;

    @Mock
    private AnalysisResultRepository analysisResultRepository;

    private AnalysisResultCallbackService service;
    private JsonMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        AnalysisResultPersistService persistService = new AnalysisResultPersistService(
                analysisJobRepository,
                analysisResultRepository,
                objectMapper
        );
        service = new AnalysisResultCallbackService(persistService);
        lenient().when(analysisJobRepository.save(any(AnalysisJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(analysisResultRepository.save(any(AnalysisResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void pendingToCompletedPersistsResultAndJob() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 2)));

        ArgumentCaptor<AnalysisResult> resultCaptor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(analysisResultRepository).save(resultCaptor.capture());
        AnalysisResult saved = resultCaptor.getValue();

        assertEquals(AnalysisJob.STATUS_COMPLETED, job.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 5), job.getFinishedAt());
        assertNull(job.getErrorCode());
        assertNull(job.getErrorMessage());
        assertEquals(job.getId(), saved.getAnalysisJobId());
        assertEquals(Boolean.TRUE, saved.getIsValid());
        assertEquals(Boolean.TRUE, saved.getViolationDetected());
        assertNull(saved.getResultS3Key());
        assertTrue(saved.getSummary().contains("\"violation_count\":2"));
        assertEquals(2, saved.getResultData().get("events").size());
        assertEquals("VIOLATION", saved.getResultData().get("events").get(0).get("event_type").asText());
        assertEquals(1000, saved.getResultData().get("summary").get("frames").intValue());
    }

    @Test
    void processingToCompleted() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_PROCESSING);
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 0)));

        assertEquals(AnalysisJob.STATUS_COMPLETED, job.getStatus());
        verify(analysisResultRepository).save(any(AnalysisResult.class));
    }

    @Test
    void violationCountZeroStoresFalse() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 0)));

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(analysisResultRepository).save(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().getViolationDetected());
    }

    @Test
    void missingViolationCountKeepsNull() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        String json = completedJson(job.getId(), null)
                .replace("\"violation_count\":null,", "");
        service.handleResult(job.getId(), payload(job.getId(), json));

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(analysisResultRepository).save(captor.capture());
        JsonNode summary = captor.getValue().getResultData().get("summary");
        assertNull(captor.getValue().getViolationDetected());
        assertTrue(summary.get("violation_count") == null || summary.get("violation_count").isMissingNode());
    }

    @Test
    void nullNumericFieldsArePreserved() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), null)));

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(analysisResultRepository).save(captor.capture());
        JsonNode summary = captor.getValue().getResultData().get("summary");
        assertTrue(summary.get("frames").isNull());
        assertTrue(summary.get("fps").isNull());
        assertTrue(summary.get("violation_count").isNull());
        assertNull(captor.getValue().getViolationDetected());
    }

    @Test
    void duplicateCallbackDoesNotOverwrite() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        job.setFinishedAt(LocalDateTime.of(2026, 11, 24, 4, 5));
        AnalysisResult existing = storedResult(job.getId(), completedJson(job.getId(), 2));
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.of(existing));

        service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 2)));

        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
        assertEquals(AnalysisJob.STATUS_COMPLETED, job.getStatus());
    }

    @Test
    void differentPayloadConflicts() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        AnalysisResult existing = storedResult(job.getId(), completedJson(job.getId(), 2));
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.of(existing));

        assertThrows(CallbackConflictException.class,
                () -> service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 0))));
        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
    }

    @Test
    void failedJobDoesNotBecomeCompleted() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_FAILED);
        job.setErrorCode("ANALYSIS_FAILED");
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        assertThrows(CallbackConflictException.class,
                () -> service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 2))));
        assertEquals(AnalysisJob.STATUS_FAILED, job.getStatus());
        assertEquals("ANALYSIS_FAILED", job.getErrorCode());
        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
    }

    @Test
    void completedJobWithoutResultConflicts() {
        AnalysisJob job = pendingJob();
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        assertThrows(CallbackConflictException.class,
                () -> service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 2))));
        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
    }

    @Test
    void unknownJob() {
        UUID jobId = UUID.randomUUID();
        when(analysisJobRepository.findByIdForUpdate(jobId)).thenReturn(Optional.empty());
        assertThrows(JobNotFoundException.class,
                () -> service.handleResult(jobId, payload(jobId, completedJson(jobId, 1))));
    }

    @Test
    void jobIdMismatch() {
        UUID pathJobId = UUID.randomUUID();
        UUID bodyJobId = UUID.randomUUID();
        assertThrows(CallbackValidationException.class,
                () -> service.handleResult(pathJobId, payload(bodyJobId, completedJson(bodyJobId, 1))));
        verify(analysisJobRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void missingStatus() {
        AnalysisJob job = pendingJob();
        String json = "{\"job_id\":\"" + job.getId() + "\",\"finished_at\":\"2026-11-24T04:05:00Z\"}";
        assertThrows(CallbackValidationException.class,
                () -> service.handleResult(job.getId(), payload(job.getId(), json)));
    }

    @Test
    void unsupportedStatus() {
        AnalysisJob job = pendingJob();
        String json = completedJson(job.getId(), 1).replace("\"COMPLETED\"", "\"PROCESSING\"");
        assertThrows(CallbackValidationException.class,
                () -> service.handleResult(job.getId(), payload(job.getId(), json)));
    }

    @Test
    void invalidFinishedAt() {
        AnalysisJob job = pendingJob();
        String json = completedJson(job.getId(), 1).replace("2026-11-24T04:05:00Z", "not-a-date");
        assertThrows(CallbackValidationException.class,
                () -> service.handleResult(job.getId(), payload(job.getId(), json)));
        verify(analysisJobRepository, never()).findByIdForUpdate(any());
        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
    }

    @Test
    void resultSaveFailureDoesNotMarkJobCompleted() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());
        when(analysisResultRepository.save(any(AnalysisResult.class)))
                .thenThrow(new DataIntegrityViolationException("db"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 1))));
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
    }

    @Test
    void jobUpdateFailurePropagatesAfterResultSave() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());
        when(analysisJobRepository.save(any(AnalysisJob.class)))
                .thenThrow(new DataIntegrityViolationException("job"));

        assertThrows(DataIntegrityViolationException.class,
                () -> service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 1))));
        verify(analysisResultRepository).save(any(AnalysisResult.class));
    }

    @Test
    void concurrentDuplicateUsesRowLock() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        service.handleResult(job.getId(), payload(job.getId(), completedJson(job.getId(), 1)));

        InOrder order = inOrder(analysisJobRepository, analysisResultRepository);
        order.verify(analysisJobRepository).findByIdForUpdate(job.getId());
        order.verify(analysisResultRepository).findByAnalysisJobId(job.getId());
        order.verify(analysisResultRepository).save(any(AnalysisResult.class));
        order.verify(analysisJobRepository).save(any(AnalysisJob.class));
    }

    @Test
    void plusOffsetFinishedAtIsParsed() {
        AnalysisJob job = pendingJob();
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());
        String json = completedJson(job.getId(), 1).replace("Z\"", "+00:00\"");

        service.handleResult(job.getId(), payload(job.getId(), json));

        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 5), job.getFinishedAt());
    }

    private AnalysisJob pendingJob() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setErrorCode("RECOVERY_NOT_IMPLEMENTED");
        job.setErrorMessage("waiting");
        return job;
    }

    private AnalysisResult storedResult(UUID jobId, String json) {
        AnalysisResult result = new AnalysisResult();
        result.setId(UUID.randomUUID());
        result.setAnalysisJobId(jobId);
        result.setResultData(payload(jobId, json));
        result.setIsValid(Boolean.TRUE);
        result.setResultS3Key(null);
        return result;
    }

    private JsonNode payload(UUID jobId, String json) {
        return objectMapper.readTree(json.replace("__JOB_ID__", jobId.toString()));
    }

    private String completedJson(UUID jobId, Integer violationCount) {
        String violationJson = violationCount == null ? "null" : violationCount.toString();
        String framesJson = violationCount == null ? "null" : "1000";
        String fpsJson = violationCount == null ? "null" : "30.0";
        return "{\"job_id\":\"" + jobId + "\",\"status\":\"COMPLETED\","
                + "\"finished_at\":\"2026-11-24T04:05:00Z\",\"is_valid\":true,"
                + "\"summary\":{\"frames\":" + framesJson + ",\"track_count\":10,\"crossed_count\":3,"
                + "\"violation_count\":" + violationJson + ",\"fps\":" + fpsJson + ",\"processing_ms\":15000},"
                + "\"events\":[{\"event_type\":\"VIOLATION\",\"frame\":120,\"track_id\":3,"
                + "\"evidence_s3_key\":\"results/example.jpg\"},"
                + "{\"event_type\":\"line_cross\",\"frame\":8,\"track_id\":9,\"evidence_s3_key\":null}],"
                + "\"result_video_s3_key\":null}";
    }
}
