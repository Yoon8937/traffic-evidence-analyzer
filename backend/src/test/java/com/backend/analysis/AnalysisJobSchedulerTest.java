package com.backend.analysis;

import com.backend.video.Video;
import com.backend.video.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisJobSchedulerTest {

    @Mock
    private AnalysisJobClaimService claimService;
    @Mock
    private AnalysisJobService analysisJobService;
    @Mock
    private VideoRepository videoRepository;
    @Mock
    private FastApiAnalysisClient fastApiAnalysisClient;

    private AnalysisSchedulerProperties properties;
    private AnalysisJobScheduler scheduler;

    @BeforeEach
    void setUp() {
        properties = new AnalysisSchedulerProperties();
        scheduler = new AnalysisJobScheduler(
                properties,
                claimService,
                analysisJobService,
                videoRepository,
                fastApiAnalysisClient
        );
    }

    @Test
    void dispatchRetriesRetryableJobsAndIsolatesFailures() {
        AnalysisJob first = pendingRetryJob();
        AnalysisJob second = pendingRetryJob();
        ClaimedJob firstClaimed = claimed(first, AnalysisJob.SCHEDULER_TASK_DISPATCH);
        ClaimedJob secondClaimed = claimed(second, AnalysisJob.SCHEDULER_TASK_DISPATCH);
        Video video = new Video();
        video.setS3Key("videos/a.MOV");
        when(claimService.claimDispatchJobs(anyInt(), any(), anyInt(), eq(scheduler.instanceId())))
                .thenReturn(List.of(firstClaimed, secondClaimed));
        when(videoRepository.findById(first.getVideoId())).thenReturn(Optional.of(video));
        when(videoRepository.findById(second.getVideoId())).thenReturn(Optional.of(video));
        doThrow(new RuntimeException("first failed"))
                .when(analysisJobService).dispatchToFastApi(eq(first), any(), eq(firstClaimed.getLease()));

        scheduler.dispatchRetries();

        verify(analysisJobService).dispatchToFastApi(second, "videos/a.MOV", secondClaimed.getLease());
        verify(claimService).releaseLease(firstClaimed.getLease());
        verify(claimService).releaseLease(secondClaimed.getLease());
    }

    @Test
    void dispatchSkipsJobsWithoutObjectKey() {
        AnalysisJob job = pendingRetryJob();
        ClaimedJob claimed = claimed(job, AnalysisJob.SCHEDULER_TASK_DISPATCH);
        when(claimService.claimDispatchJobs(anyInt(), any(), anyInt(), eq(scheduler.instanceId())))
                .thenReturn(List.of(claimed));
        when(videoRepository.findById(job.getVideoId())).thenReturn(Optional.empty());

        scheduler.dispatchRetries();

        verify(analysisJobService, never()).dispatchToFastApi(any(), any(), any());
        verify(claimService).releaseLease(claimed.getLease());
    }

    @Test
    void recoverStatusesProcessClaimedJobs() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setAcceptedAt(LocalDateTime.now());
        ClaimedJob claimed = claimed(job, AnalysisJob.SCHEDULER_TASK_RECOVERY);
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setOutcome(FastApiJobStatusResponse.OUTCOME_REMOTE);
        response.setStatus("ACCEPTED");
        when(claimService.claimRecoveryJobs(anyInt(), any(), anyInt(), eq(scheduler.instanceId())))
                .thenReturn(List.of(claimed));
        when(analysisJobService.recoverJobStatus(job, claimed.getLease())).thenReturn(response);

        scheduler.recoverStatuses();

        verify(analysisJobService).recoverJobStatus(job, claimed.getLease());
        verify(claimService).releaseLease(claimed.getLease());
    }

    @Test
    void timeoutCompletedUsesSharedPersistPath() {
        AnalysisJob job = processingJob();
        ClaimedJob claimed = claimed(job, AnalysisJob.SCHEDULER_TASK_TIMEOUT);
        FastApiJobStatusResponse response = remote("COMPLETED");
        when(claimService.claimTimeoutJobs(anyInt(), any(), any(), anyInt(), eq(scheduler.instanceId())))
                .thenReturn(List.of(claimed));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        scheduler.checkProcessingTimeouts();

        verify(analysisJobService).applyFetchedStatus(job, response, claimed.getLease());
        verify(analysisJobService, never()).markProcessingTimeout(any(), any());
        verify(claimService).releaseLease(claimed.getLease());
    }

    @Test
    void timeoutFailedUsesExistingFailedPath() {
        AnalysisJob job = processingJob();
        ClaimedJob claimed = claimed(job, AnalysisJob.SCHEDULER_TASK_TIMEOUT);
        FastApiJobStatusResponse response = remote("FAILED");
        when(claimService.claimTimeoutJobs(anyInt(), any(), any(), anyInt(), eq(scheduler.instanceId())))
                .thenReturn(List.of(claimed));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        scheduler.checkProcessingTimeouts();

        verify(analysisJobService).applyFetchedStatus(job, response, claimed.getLease());
        verify(analysisJobService, never()).markProcessingTimeout(any(), any());
    }

    @Test
    void timeoutStillProcessingMarksFailed() {
        AnalysisJob job = processingJob();
        ClaimedJob claimed = claimed(job, AnalysisJob.SCHEDULER_TASK_TIMEOUT);
        FastApiJobStatusResponse response = remote("PROCESSING");
        when(claimService.claimTimeoutJobs(anyInt(), any(), any(), anyInt(), eq(scheduler.instanceId())))
                .thenReturn(List.of(claimed));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        scheduler.checkProcessingTimeouts();

        verify(analysisJobService).markProcessingTimeout(job, claimed.getLease());
    }

    @Test
    void timeoutTransportErrorDoesNotFailJob() {
        AnalysisJob job = processingJob();
        ClaimedJob claimed = claimed(job, AnalysisJob.SCHEDULER_TASK_TIMEOUT);
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setOutcome(FastApiJobStatusResponse.OUTCOME_TIMEOUT);
        when(claimService.claimTimeoutJobs(anyInt(), any(), any(), anyInt(), eq(scheduler.instanceId())))
                .thenReturn(List.of(claimed));
        when(fastApiAnalysisClient.getJobStatus(job.getId())).thenReturn(response);

        scheduler.checkProcessingTimeouts();

        verify(analysisJobService, never()).markProcessingTimeout(any(), any());
        verify(analysisJobService, never()).applyFetchedStatus(any(), any(), any());
        verify(claimService).releaseLease(claimed.getLease());
    }

    private ClaimedJob claimed(AnalysisJob job, String task) {
        return new ClaimedJob(
                job,
                new SchedulerLease(job.getId(), scheduler.instanceId(), task, UUID.randomUUID().toString())
        );
    }

    private AnalysisJob pendingRetryJob() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setErrorCode(FastApiAcceptanceResult.ERROR_QUEUE_FULL);
        return job;
    }

    private AnalysisJob processingJob() {
        AnalysisJob job = AnalysisJob.pendingJob(UUID.randomUUID());
        job.setStatus(AnalysisJob.STATUS_PROCESSING);
        job.setStartedAt(LocalDateTime.now().minusHours(1));
        return job;
    }

    private FastApiJobStatusResponse remote(String status) {
        FastApiJobStatusResponse response = new FastApiJobStatusResponse();
        response.setOutcome(FastApiJobStatusResponse.OUTCOME_REMOTE);
        response.setStatus(status);
        return response;
    }
}
