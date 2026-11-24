package com.backend.analysis;

import com.backend.exception.CallbackConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisResultPersistServiceTest {

    @Mock
    private AnalysisJobRepository analysisJobRepository;
    @Mock
    private AnalysisResultRepository analysisResultRepository;

    private AnalysisResultPersistService service;
    private JsonMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        service = new AnalysisResultPersistService(
                analysisJobRepository,
                analysisResultRepository,
                objectMapper
        );
        lenient().when(analysisJobRepository.save(any(AnalysisJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(analysisResultRepository.save(any(AnalysisResult.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void persistFromGetStoresJsonbAndCompletesJob() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());

        FastApiJobStatusResponse response = completedGet();
        service.persistFromGet(job.getId(), response);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(analysisResultRepository).save(captor.capture());
        AnalysisResult saved = captor.getValue();
        assertEquals(AnalysisJob.STATUS_COMPLETED, job.getStatus());
        assertEquals(LocalDateTime.of(2026, 11, 24, 4, 5), job.getFinishedAt());
        assertEquals(Boolean.TRUE, saved.getIsValid());
        assertEquals(Boolean.TRUE, saved.getViolationDetected());
        assertEquals("results/video.mp4", saved.getResultS3Key());
        assertEquals(job.getId().toString(), saved.getResultData().get("job_id").asText());
        assertEquals(12, saved.getResultData().get("summary").get("frames").intValue());
        assertEquals("line_cross", saved.getResultData().get("events").get(0).get("event_type").asText());
        assertFalse(saved.getResultData().has("callback_delivered"));
    }

    @Test
    void persistFromGetOmitsMissingFields() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setStatus("COMPLETED");

        service.persistFromGet(job.getId(), response);

        ArgumentCaptor<AnalysisResult> captor = ArgumentCaptor.forClass(AnalysisResult.class);
        verify(analysisResultRepository).save(captor.capture());
        JsonNode data = captor.getValue().getResultData();
        assertEquals(job.getId().toString(), data.get("job_id").asText());
        assertEquals("COMPLETED", data.get("status").asText());
        assertFalse(data.has("finished_at"));
        assertFalse(data.has("is_valid"));
        assertFalse(data.has("summary"));
        assertFalse(data.has("events"));
        assertFalse(data.has("result_video_s3_key"));
        assertNull(job.getFinishedAt());
    }

    @Test
    void getAndCallbackPayloadsAreIdempotent() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        FastApiJobStatusResponse response = completedGet();
        JsonNode payload = service.toGetPayload(job.getId(), response);
        AnalysisResult existing = new AnalysisResult();
        existing.setResultData(payload);
        existing.setIsValid(Boolean.TRUE);
        existing.setResultS3Key("results/video.mp4");
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.of(existing));

        service.persistFromCallback(job.getId(), payload);

        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
    }

    @Test
    void conflictingGetDoesNotOverwrite() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        AnalysisResult existing = new AnalysisResult();
        existing.setResultData(service.toGetPayload(job.getId(), completedGet()));
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.of(existing));
        FastApiJobStatusResponse other = completedGet();
        other.setIsValid(Boolean.FALSE);

        assertThrows(CallbackConflictException.class, () -> service.persistFromGet(job.getId(), other));
        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
    }

    @Test
    void nullAndOmittedEventFieldsAreTreatedAsTheSameResult() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        JsonNode stored = objectMapper.readTree("""
                {"is_valid":true,"summary":{"frames":12,"fps":null},"events":[{"event_type":"line_cross","frame":null,"track_id":9}],"result_video_s3_key":"results/video.mp4"}
                """);
        JsonNode incoming = objectMapper.readTree("""
                {"finished_at":"2026-11-24T04:05:00Z","is_valid":true,"summary":{"frames":12},"events":[{"event_type":"line_cross","track_id":9}],"result_video_s3_key":"results/video.mp4"}
                """);
        AnalysisResult existing = new AnalysisResult();
        existing.setResultData(stored);
        existing.setIsValid(Boolean.TRUE);
        existing.setResultS3Key("results/video.mp4");
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.of(existing));

        service.persistFromCallback(job.getId(), incoming);

        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
        assertEquals(AnalysisJob.STATUS_COMPLETED, job.getStatus());
        assertEquals(stored, existing.getResultData());
    }

    @Test
    void getPayloadOmittingNullsMatchesStoredCallbackJson() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setStatus(AnalysisJob.STATUS_PROCESSING);
        JsonNode stored = objectMapper.readTree("""
                {"is_valid":true,"summary":{"frames":12,"fps":null},"events":[{"event_type":"line_cross","frame":4,"track_id":null,"evidence_s3_key":null}],"result_video_s3_key":"results/video.mp4"}
                """);
        AnalysisResult existing = new AnalysisResult();
        existing.setResultData(stored);
        existing.setIsValid(Boolean.TRUE);
        existing.setResultS3Key("results/video.mp4");
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.of(existing));
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setStatus("COMPLETED");
        response.setIsValid(Boolean.TRUE);
        FastApiJobSummary summary = new FastApiJobSummary();
        summary.setFrames(12L);
        response.setSummary(summary);
        FastApiJobEvent event = new FastApiJobEvent();
        event.setEventType("line_cross");
        event.setFrame(4L);
        response.setEvents(List.of(event));
        response.setResultVideoS3Key("results/video.mp4");

        service.persistFromGet(job.getId(), response);

        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
        assertEquals(AnalysisJob.STATUS_PROCESSING, job.getStatus());
        assertEquals(stored, existing.getResultData());
    }

    @Test
    void differentAnalysisValuesStillConflict() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setStatus(AnalysisJob.STATUS_COMPLETED);
        JsonNode stored = objectMapper.readTree("""
                {"is_valid":true,"summary":{"frames":12},"events":[{"event_type":"line_cross","track_id":9}],"result_video_s3_key":"results/video.mp4"}
                """);
        JsonNode incoming = objectMapper.readTree("""
                {"finished_at":"2026-11-24T04:05:00Z","is_valid":true,"summary":{"frames":99},"events":[{"event_type":"line_cross","track_id":9}],"result_video_s3_key":"results/video.mp4"}
                """);
        AnalysisResult existing = new AnalysisResult();
        existing.setResultData(stored);
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.of(existing));

        assertThrows(CallbackConflictException.class, () -> service.persistFromCallback(job.getId(), incoming));
        verify(analysisResultRepository, never()).save(any(AnalysisResult.class));
        assertEquals(stored, existing.getResultData());
        assertEquals(12, existing.getResultData().get("summary").get("frames").intValue());
    }

    @Test
    void resultSaveFailureLeavesJobPending() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        when(analysisJobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(analysisResultRepository.findByAnalysisJobId(job.getId())).thenReturn(Optional.empty());
        when(analysisResultRepository.save(any(AnalysisResult.class)))
                .thenThrow(new IllegalStateException("db"));

        assertThrows(IllegalStateException.class, () -> service.persistFromGet(job.getId(), completedGet()));
        assertEquals(AnalysisJob.STATUS_PENDING, job.getStatus());
        verify(analysisJobRepository, never()).save(any(AnalysisJob.class));
    }

    private FastApiJobStatusResponse completedGet() {
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setStatus("COMPLETED");
        response.setFinishedAt(LocalDateTime.of(2026, 11, 24, 4, 5));
        response.setIsValid(Boolean.TRUE);
        FastApiJobSummary summary = new FastApiJobSummary();
        summary.setFrames(12L);
        summary.setTrackCount(3L);
        summary.setCrossedCount(2L);
        summary.setViolationCount(1L);
        summary.setFps(29.97d);
        summary.setProcessingMs(840L);
        response.setSummary(summary);
        FastApiJobEvent event = new FastApiJobEvent();
        event.setEventType("line_cross");
        event.setFrame(4L);
        event.setTrackId(9L);
        event.setEvidenceS3Key("evidence/a.jpg");
        response.setEvents(List.of(event));
        response.setResultVideoS3Key("results/video.mp4");
        response.setCallbackDelivered(Boolean.FALSE);
        return response;
    }
}
