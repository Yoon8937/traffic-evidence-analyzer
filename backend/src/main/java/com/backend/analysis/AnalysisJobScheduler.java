package com.backend.analysis;

import com.backend.video.Video;
import com.backend.video.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "app.analysis.scheduler", name = "enabled", havingValue = "true")
public class AnalysisJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobScheduler.class);

    private final AnalysisSchedulerProperties properties;
    private final AnalysisJobClaimService claimService;
    private final AnalysisJobService analysisJobService;
    private final VideoRepository videoRepository;
    private final FastApiAnalysisClient fastApiAnalysisClient;
    private final String instanceId;

    public AnalysisJobScheduler(
            AnalysisSchedulerProperties properties,
            AnalysisJobClaimService claimService,
            AnalysisJobService analysisJobService,
            VideoRepository videoRepository,
            FastApiAnalysisClient fastApiAnalysisClient
    ) {
        this.properties = properties;
        this.claimService = claimService;
        this.analysisJobService = analysisJobService;
        this.videoRepository = videoRepository;
        this.fastApiAnalysisClient = fastApiAnalysisClient;
        this.instanceId = UUID.randomUUID().toString();
    }

    @Scheduled(fixedDelayString = "${app.analysis.scheduler.dispatch-interval-ms:1000}")
    public void dispatchRetries() {
        runBatch(AnalysisJob.SCHEDULER_TASK_DISPATCH, this::claimDispatch, this::retryDispatch);
    }

    @Scheduled(fixedDelayString = "${app.analysis.scheduler.recovery-interval-ms:10000}")
    public void recoverStatuses() {
        runBatch(AnalysisJob.SCHEDULER_TASK_RECOVERY, this::claimRecovery, this::recoverOne);
    }

    @Scheduled(fixedDelayString = "${app.analysis.scheduler.timeout-interval-ms:30000}")
    public void checkProcessingTimeouts() {
        runBatch(AnalysisJob.SCHEDULER_TASK_TIMEOUT, this::claimTimeout, this::timeoutOne);
    }

    String instanceId() {
        return instanceId;
    }

    private List<ClaimedJob> claimDispatch() {
        LocalDateTime now = LocalDateTime.now();
        return claimService.claimDispatchJobs(
                properties.getScheduler().getBatchSize(),
                now,
                properties.getScheduler().getLeaseSeconds(),
                instanceId
        );
    }

    private List<ClaimedJob> claimRecovery() {
        LocalDateTime now = LocalDateTime.now();
        return claimService.claimRecoveryJobs(
                properties.getScheduler().getBatchSize(),
                now,
                properties.getScheduler().getLeaseSeconds(),
                instanceId
        );
    }

    private List<ClaimedJob> claimTimeout() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startedBefore = now.minusMinutes(properties.getProcessingTimeoutMinutes());
        return claimService.claimTimeoutJobs(
                properties.getScheduler().getBatchSize(),
                now,
                startedBefore,
                properties.getScheduler().getLeaseSeconds(),
                instanceId
        );
    }

    private void retryDispatch(ClaimedJob claimed) {
        AnalysisJob job = claimed.getJob();
        Video video = videoRepository.findById(job.getVideoId()).orElse(null);
        if (video == null || video.getS3Key() == null || video.getS3Key().isBlank()) {
            log.error("POST retry skipped because video object key is missing");
            return;
        }
        analysisJobService.dispatchToFastApi(job, video.getS3Key(), claimed.getLease());
    }

    private void recoverOne(ClaimedJob claimed) {
        FastApiJobStatusResponse response = analysisJobService.recoverJobStatus(
                claimed.getJob(),
                claimed.getLease()
        );
        if (response != null) {
            MDC.put("status", response.getStatus());
            log.info("GET recovery processed. outcome={}, remoteStatus={}",
                    response.getOutcome(), response.getStatus());
        }
    }

    private void timeoutOne(ClaimedJob claimed) {
        AnalysisJob job = claimed.getJob();
        log.info("PROCESSING timeout detected");
        FastApiJobStatusResponse response = fastApiAnalysisClient.getJobStatus(job.getId());
        if (response == null || !FastApiJobStatusResponse.OUTCOME_REMOTE.equals(response.getOutcome())) {
            log.warn("PROCESSING timeout GET failed. outcome={}",
                    response == null ? "null" : response.getOutcome());
            return;
        }
        MDC.put("status", response.getStatus());
        if ("COMPLETED".equals(response.getStatus()) || "FAILED".equals(response.getStatus())
                || "ACCEPTED".equals(response.getStatus())) {
            analysisJobService.applyFetchedStatus(job, response, claimed.getLease());
            return;
        }
        if ("PROCESSING".equals(response.getStatus())) {
            analysisJobService.markProcessingTimeout(job, claimed.getLease());
        }
    }

    private void runBatch(String schedulerType, Claimer claimer, JobWorker worker) {
        MDC.put("scheduler_type", schedulerType);
        MDC.put("trace_id", UUID.randomUUID().toString());
        long started = System.nanoTime();
        log.info("scheduler started");
        try {
            List<ClaimedJob> claimed = claimer.claim();
            log.info("scheduler claimed jobs. count={}", claimed.size());
            for (ClaimedJob item : claimed) {
                processOne(schedulerType, item, worker);
            }
        } catch (RuntimeException e) {
            log.error("scheduler batch failed. error_type={}", e.getClass().getSimpleName());
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            MDC.put("duration_ms", Long.toString(elapsedMs));
            log.info("scheduler finished");
            MDC.remove("duration_ms");
            MDC.remove("scheduler_type");
            MDC.remove("trace_id");
            MDC.remove("job_id");
            MDC.remove("video_id");
            MDC.remove("status");
            MDC.remove("error_type");
        }
    }

    private void processOne(String schedulerType, ClaimedJob claimed, JobWorker worker) {
        AnalysisJob job = claimed.getJob();
        MDC.put("job_id", job.getId() == null ? "" : job.getId().toString());
        MDC.put("video_id", job.getVideoId() == null ? "" : job.getVideoId().toString());
        MDC.put("status", job.getStatus());
        long started = System.nanoTime();
        try {
            worker.process(claimed);
        } catch (RuntimeException e) {
            MDC.put("error_type", e.getClass().getSimpleName());
            log.error("scheduler job failed");
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            MDC.put("duration_ms", Long.toString(elapsedMs));
            try {
                claimService.releaseLease(claimed.getLease());
            } catch (RuntimeException e) {
                log.error("lease release failed. error_type={}", e.getClass().getSimpleName());
            }
            log.info("scheduler job finished. scheduler_type={}", schedulerType);
            MDC.remove("duration_ms");
            MDC.remove("error_type");
        }
    }

    @FunctionalInterface
    private interface Claimer {
        List<ClaimedJob> claim();
    }

    @FunctionalInterface
    private interface JobWorker {
        void process(ClaimedJob claimed);
    }
}
