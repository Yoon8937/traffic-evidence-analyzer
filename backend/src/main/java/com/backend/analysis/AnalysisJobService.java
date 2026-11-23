package com.backend.analysis;

import com.backend.video.Video;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class AnalysisJobService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);

    public static final int FIRST_RETRY_DELAY_SECONDS = 1;
    public static final int SECOND_RETRY_DELAY_SECONDS = 2;

    public static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
            FastApiAcceptanceResult.ERROR_QUEUE_FULL,
            FastApiAcceptanceResult.ERROR_WORKER_UNAVAILABLE,
            FastApiAcceptanceResult.ERROR_SERVER,
            FastApiAcceptanceResult.ERROR_TIMEOUT,
            FastApiAcceptanceResult.ERROR_CONNECTION,
            FastApiAcceptanceResult.ERROR_REQUEST_FAILED
    );

    public static final Set<String> STATUS_RECOVERY_ERROR_CODES = Set.of(
            AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED,
            FastApiAcceptanceResult.ERROR_RESPONSE_INVALID
    );

    private final AnalysisJobRepository analysisJobRepository;
    private final FastApiAnalysisClient fastApiAnalysisClient;

    public AnalysisJobService(
            AnalysisJobRepository analysisJobRepository,
            FastApiAnalysisClient fastApiAnalysisClient
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.fastApiAnalysisClient = fastApiAnalysisClient;
    }

    public AnalysisJob newPendingJob(Video video) {
        return AnalysisJob.pendingJob(video.getId());
    }

    public List<AnalysisJob> findJobsDueForRetry(LocalDateTime now) {
        return analysisJobRepository.findPendingRetriesDue(
                AnalysisJob.STATUS_PENDING,
                AnalysisJob.MAX_DISPATCH_RESENDS,
                RETRYABLE_ERROR_CODES,
                now.minusSeconds(FIRST_RETRY_DELAY_SECONDS),
                now.minusSeconds(SECOND_RETRY_DELAY_SECONDS)
        );
    }

    public List<AnalysisJob> findJobsNeedingStatusRecovery() {
        return analysisJobRepository.findJobsNeedingStatusRecovery(
                AnalysisJob.STATUS_PENDING,
                STATUS_RECOVERY_ERROR_CODES
        );
    }

    public FastApiJobStatusResponse checkFastApiJobStatus(AnalysisJob job) {
        return recoverJobStatus(job);
    }

    public List<FastApiJobStatusResponse> recoverJobStatus() {
        List<FastApiJobStatusResponse> responses = new ArrayList<FastApiJobStatusResponse>();
        for (AnalysisJob job : findJobsNeedingStatusRecovery()) {
            responses.add(recoverJobStatus(job));
        }
        return responses;
    }

    public FastApiJobStatusResponse recoverJobStatus(AnalysisJob job) {
        FastApiJobStatusResponse response = fastApiAnalysisClient.getJobStatus(job.getId());
        applyStatusResponse(job, response);
        return response;
    }

    public void dispatchToFastApi(AnalysisJob job, String objectKey) {
        putJobContext(job);
        MDC.put("status", job.getStatus());
        try {
            if (job.getAcceptedAt() != null) {
                log.info("FastAPI dispatch skipped because the job is already accepted");
                return;
            }
            if (AnalysisJob.STATUS_FAILED.equals(job.getStatus())) {
                return;
            }
            if (job.getErrorCode() != null && !RETRYABLE_ERROR_CODES.contains(job.getErrorCode())) {
                log.warn("FastAPI dispatch skipped. GET recovery is not implemented");
                return;
            }
            if (isAwaitingRetry(job)) {
                int resends = resendCount(job);
                if (resends >= AnalysisJob.MAX_DISPATCH_RESENDS) {
                    markRetryExhausted(job, job.getErrorCode(), job.getErrorMessage());
                    return;
                }
                if (!isRetryDue(job, resends)) {
                    log.info("FastAPI retry is waiting");
                    return;
                }
                job.setRetryCount(resends + 1);
                job.setUpdatedAt(LocalDateTime.now());
                analysisJobRepository.save(job);
            }

            log.info("FastAPI analysis request started");
            // 동시 제출 상한은 이 submit 직전에 둔다. 업로드 경로와 나중 재시도 호출이 같은 지점을 지난다.
            FastApiAcceptanceResult result = fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), objectKey);
            applyResult(job, result);
        } catch (RuntimeException e) {
            MDC.put("error_type", e.getClass().getSimpleName());
            log.error("FastAPI request failed");
            scheduleOrExhaust(job, FastApiAcceptanceResult.ERROR_REQUEST_FAILED, "FastAPI request failed");
        } finally {
            clearJobContext();
        }
    }

    private void applyResult(AnalysisJob job, FastApiAcceptanceResult result) {
        MDC.put("status", job.getStatus());
        if (responseJobMismatches(job, result)) {
            log.error("FastAPI response job_id did not match");
            rememberNonRetry(job, FastApiAcceptanceResult.ERROR_RESPONSE_INVALID, "FastAPI response job_id did not match");
            return;
        }
        if (result.isAccepted()) {
            job.setStatus(AnalysisJob.STATUS_PENDING);
            job.setAcceptedAt(result.getAcceptedAt());
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setUpdatedAt(LocalDateTime.now());
            analysisJobRepository.save(job);
            log.info("FastAPI job accepted");
            log.info("accepted_at updated");
            return;
        }
        if (result.isRemoteExists()) {
            job.setStatus(AnalysisJob.STATUS_PENDING);
            job.setErrorCode(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED);
            job.setErrorMessage("GET recovery is not implemented");
            job.setUpdatedAt(LocalDateTime.now());
            analysisJobRepository.save(job);
            log.warn("FastAPI job already exists with status {}. GET recovery is not implemented", result.getRemoteStatus());
            return;
        }
        if (FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR.equals(result.getOutcome())) {
            MDC.put("error_type", result.getErrorCode());
            job.setStatus(AnalysisJob.STATUS_FAILED);
            job.setErrorCode(result.getErrorCode());
            job.setErrorMessage(result.getErrorMessage());
            job.setUpdatedAt(LocalDateTime.now());
            analysisJobRepository.save(job);
            log.error("FastAPI request failed");
            return;
        }
        if (result.isRetryable()) {
            MDC.put("error_type", result.getErrorCode());
            if (FastApiAcceptanceResult.OUTCOME_TIMEOUT.equals(result.getOutcome())) {
                log.error("FastAPI request timed out");
            } else {
                log.error("FastAPI request failed");
            }
            scheduleOrExhaust(job, result.getErrorCode(), result.getErrorMessage());
            return;
        }

        MDC.put("error_type", result.getErrorCode());
        log.error("FastAPI request failed");
        rememberNonRetry(job, result.getErrorCode(), result.getErrorMessage());
    }

    private void scheduleOrExhaust(AnalysisJob job, String errorCode, String errorMessage) {
        if (resendCount(job) >= AnalysisJob.MAX_DISPATCH_RESENDS) {
            markRetryExhausted(job, errorCode, errorMessage);
            return;
        }
        job.setStatus(AnalysisJob.STATUS_PENDING);
        job.setErrorCode(errorCode);
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(LocalDateTime.now());
        analysisJobRepository.save(job);
    }

    private void markRetryExhausted(AnalysisJob job, String lastErrorCode, String lastErrorMessage) {
        job.setStatus(AnalysisJob.STATUS_FAILED);
        job.setErrorCode(AnalysisJob.ERROR_DISPATCH_RETRY_EXHAUSTED);
        if (lastErrorMessage == null || lastErrorMessage.isBlank()) {
            job.setErrorMessage("FastAPI dispatch retry cap exceeded");
        } else {
            job.setErrorMessage(lastErrorMessage);
        }
        job.setUpdatedAt(LocalDateTime.now());
        analysisJobRepository.save(job);
        MDC.put("error_type", AnalysisJob.ERROR_DISPATCH_RETRY_EXHAUSTED);
        log.error("FastAPI dispatch retry cap exceeded. lastErrorCode={}", lastErrorCode);
    }

    private void rememberNonRetry(AnalysisJob job, String errorCode, String errorMessage) {
        job.setStatus(AnalysisJob.STATUS_PENDING);
        job.setErrorCode(errorCode);
        job.setErrorMessage(errorMessage);
        job.setUpdatedAt(LocalDateTime.now());
        analysisJobRepository.save(job);
    }

    private void applyStatusResponse(AnalysisJob job, FastApiJobStatusResponse response) {
        if (response == null) {
            return;
        }
        if (FastApiJobStatusResponse.OUTCOME_NOT_FOUND.equals(response.getOutcome())) {
            rememberGetNotFound(job);
            return;
        }
        if (!FastApiJobStatusResponse.OUTCOME_REMOTE.equals(response.getOutcome())) {
            return;
        }
        if ("ACCEPTED".equals(response.getStatus())) {
            applyAcceptedStatus(job, response);
            return;
        }
        if ("FAILED".equals(response.getStatus())) {
            applyFailedStatus(job, response);
        }
    }

    private void applyAcceptedStatus(AnalysisJob job, FastApiJobStatusResponse response) {
        if (isTerminalStatus(job) || response.getAcceptedAt() == null) {
            return;
        }
        job.setAcceptedAt(response.getAcceptedAt());
        job.setErrorCode(null);
        job.setErrorMessage(null);
        job.setUpdatedAt(LocalDateTime.now());
        analysisJobRepository.save(job);
    }

    private void applyFailedStatus(AnalysisJob job, FastApiJobStatusResponse response) {
        if (AnalysisJob.STATUS_COMPLETED.equals(job.getStatus())) {
            return;
        }
        job.setStatus(AnalysisJob.STATUS_FAILED);
        if (response.getErrorCode() != null) {
            job.setErrorCode(response.getErrorCode());
        }
        if (response.getErrorMessage() != null) {
            job.setErrorMessage(response.getErrorMessage());
        }
        if (response.getFinishedAt() != null) {
            job.setFinishedAt(response.getFinishedAt());
        }
        job.setUpdatedAt(LocalDateTime.now());
        analysisJobRepository.save(job);
    }

    private void rememberGetNotFound(AnalysisJob job) {
        if (isTerminalStatus(job) || job.getErrorCode() != null) {
            return;
        }
        job.setErrorCode(AnalysisJob.ERROR_RECOVERY_NOT_IMPLEMENTED);
        job.setErrorMessage("GET recovery is still needed");
        job.setUpdatedAt(LocalDateTime.now());
        analysisJobRepository.save(job);
    }

    private boolean isTerminalStatus(AnalysisJob job) {
        return AnalysisJob.STATUS_FAILED.equals(job.getStatus())
                || AnalysisJob.STATUS_COMPLETED.equals(job.getStatus());
    }

    private boolean isAwaitingRetry(AnalysisJob job) {
        return AnalysisJob.STATUS_PENDING.equals(job.getStatus())
                && job.getErrorCode() != null
                && RETRYABLE_ERROR_CODES.contains(job.getErrorCode());
    }

    private boolean isRetryDue(AnalysisJob job, int resendsAlreadySent) {
        LocalDateTime updatedAt = job.getUpdatedAt();
        if (updatedAt == null) {
            return true;
        }
        long delaySeconds = resendsAlreadySent <= 0
                ? FIRST_RETRY_DELAY_SECONDS
                : SECOND_RETRY_DELAY_SECONDS;
        return !updatedAt.plusSeconds(delaySeconds).isAfter(LocalDateTime.now());
    }

    private boolean responseJobMismatches(AnalysisJob job, FastApiAcceptanceResult result) {
        String responseJobId = result.getResponseJobId();
        if (responseJobId == null || job.getId() == null) {
            return false;
        }
        return !responseJobId.equals(job.getId().toString());
    }

    private int resendCount(AnalysisJob job) {
        return job.getRetryCount() == null ? 0 : job.getRetryCount();
    }

    private void putJobContext(AnalysisJob job) {
        if (job.getId() != null) {
            MDC.put("job_id", job.getId().toString());
        }
        if (job.getVideoId() != null) {
            MDC.put("video_id", job.getVideoId().toString());
        }
    }

    private void clearJobContext() {
        MDC.remove("job_id");
        MDC.remove("video_id");
        MDC.remove("duration_ms");
        MDC.remove("status");
        MDC.remove("error_type");
    }
}
