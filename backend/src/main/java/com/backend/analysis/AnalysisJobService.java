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
import java.util.UUID;
import java.util.function.Consumer;

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
            FastApiAcceptanceResult.ERROR_RESPONSE_INVALID,
            AnalysisJob.ERROR_GET_NOT_FOUND
    );

    private final AnalysisJobRepository analysisJobRepository;
    private final FastApiAnalysisClient fastApiAnalysisClient;
    private final AnalysisResultPersistService analysisResultPersistService;
    private final AnalysisJobClaimService analysisJobClaimService;

    public AnalysisJobService(
            AnalysisJobRepository analysisJobRepository,
            FastApiAnalysisClient fastApiAnalysisClient,
            AnalysisResultPersistService analysisResultPersistService,
            AnalysisJobClaimService analysisJobClaimService
    ) {
        this.analysisJobRepository = analysisJobRepository;
        this.fastApiAnalysisClient = fastApiAnalysisClient;
        this.analysisResultPersistService = analysisResultPersistService;
        this.analysisJobClaimService = analysisJobClaimService;
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
        return recoverJobStatus(job, null);
    }

    public FastApiJobStatusResponse recoverJobStatus(AnalysisJob job, SchedulerLease lease) {
        FastApiJobStatusResponse response = fastApiAnalysisClient.getJobStatus(job.getId());
        applyFetchedStatus(job, response, lease);
        return response;
    }

    public void applyFetchedStatus(AnalysisJob job, FastApiJobStatusResponse response) {
        applyFetchedStatus(job, response, null);
    }

    public void applyFetchedStatus(AnalysisJob job, FastApiJobStatusResponse response, SchedulerLease lease) {
        applyStatusResponse(job, response, lease);
    }

    public void dispatchToFastApi(AnalysisJob job, String objectKey) {
        dispatchToFastApi(job, objectKey, null);
    }

    public void dispatchToFastApi(AnalysisJob job, String objectKey, SchedulerLease lease) {
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
                boolean inFlight = analysisJobClaimService.isDispatchInFlight(job.getId());
                int resends = resendCount(job);
                if (resends >= AnalysisJob.MAX_DISPATCH_RESENDS && !inFlight) {
                    writeJob(job, lease, current -> markRetryExhausted(
                            current,
                            current.getErrorCode(),
                            current.getErrorMessage()
                    ), false);
                    return;
                }
                if (!inFlight) {
                    if (!isRetryDue(job, resends)) {
                        log.info("FastAPI retry is waiting");
                        return;
                    }
                    if (!reserveRetryAttempt(job, lease, resends + 1)) {
                        log.warn("FastAPI retry reserve skipped because scheduler lease is no longer held");
                        return;
                    }
                } else {
                    log.info("FastAPI dispatch resumed after in-flight interruption");
                }
            }

            log.info("FastAPI analysis request started");
            // 동시 제출 상한은 이 submit 직전에 둔다. 업로드 경로와 나중 재시도 호출이 같은 지점을 지난다.
            FastApiAcceptanceResult result = fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), objectKey);
            writeJob(job, lease, current -> applyResult(current, result), true);
        } catch (RuntimeException e) {
            MDC.put("error_type", e.getClass().getSimpleName());
            log.error("FastAPI request failed");
            writeJob(
                    job,
                    lease,
                    current -> scheduleOrExhaust(
                            current,
                            FastApiAcceptanceResult.ERROR_REQUEST_FAILED,
                            "FastAPI request failed"
                    ),
                    true
            );
        } finally {
            clearJobContext();
        }
    }

    public void markProcessingTimeout(AnalysisJob job) {
        markProcessingTimeout(job, null);
    }

    public void markProcessingTimeout(AnalysisJob job, SchedulerLease lease) {
        if (job == null || job.getId() == null) {
            return;
        }
        if (lease != null) {
            boolean applied = analysisJobClaimService.applyIfLeaseHeld(lease, this::markProcessingTimeoutLocked, false);
            if (!applied) {
                log.warn("processing timeout skipped because scheduler lease is no longer held");
            }
            return;
        }
        AnalysisJob current = reloadForUpdate(job.getId());
        if (current == null) {
            log.warn("processing timeout skipped because job row could not be locked");
            return;
        }
        markProcessingTimeoutLocked(current);
        copyJobState(current, job);
    }

    private void markProcessingTimeoutLocked(AnalysisJob job) {
        if (AnalysisJob.STATUS_COMPLETED.equals(job.getStatus())
                || AnalysisJob.STATUS_FAILED.equals(job.getStatus())) {
            log.warn("processing timeout skipped because job is already {}", job.getStatus());
            return;
        }
        if (!AnalysisJob.STATUS_PROCESSING.equals(job.getStatus())) {
            log.warn("processing timeout skipped because job status is {}", job.getStatus());
            return;
        }
        job.setStatus(AnalysisJob.STATUS_FAILED);
        job.setErrorCode(AnalysisJob.ERROR_PROCESSING_TIMEOUT);
        job.setErrorMessage("PROCESSING timeout exceeded while FastAPI still reported PROCESSING");
        job.setFinishedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());
        analysisJobRepository.save(job);
        log.warn("job marked FAILED after PROCESSING timeout");
    }

    private boolean reserveRetryAttempt(AnalysisJob job, SchedulerLease lease, int retryCount) {
        if (lease != null) {
            boolean reserved = analysisJobClaimService.reserveDispatchAttempt(lease, retryCount);
            if (reserved) {
                job.setRetryCount(retryCount);
                job.setUpdatedAt(LocalDateTime.now());
            }
            return reserved;
        }
        job.setRetryCount(retryCount);
        job.setUpdatedAt(LocalDateTime.now());
        analysisJobRepository.save(job);
        analysisJobClaimService.setDispatchInFlight(job.getId(), true);
        return true;
    }

    private void writeJob(
            AnalysisJob job,
            SchedulerLease lease,
            Consumer<AnalysisJob> writer,
            boolean clearDispatchInFlight
    ) {
        if (lease == null) {
            AnalysisJob current = reloadForUpdate(job.getId());
            if (current == null) {
                writer.accept(job);
            } else {
                writer.accept(current);
                copyJobState(current, job);
            }
            if (clearDispatchInFlight && job.getId() != null) {
                analysisJobClaimService.setDispatchInFlight(job.getId(), false);
            }
            return;
        }
        boolean applied = analysisJobClaimService.applyIfLeaseHeld(lease, fresh -> {
            writer.accept(fresh);
            copyJobState(fresh, job);
        }, clearDispatchInFlight);
        if (!applied) {
            log.warn("job write skipped because scheduler lease is no longer held");
        }
    }

    private AnalysisJob reloadForUpdate(UUID jobId) {
        if (jobId == null) {
            return null;
        }
        return analysisJobRepository.findByIdForUpdate(jobId).orElse(null);
    }

    private void copyJobState(AnalysisJob source, AnalysisJob target) {
        if (source == null || target == null || source == target) {
            return;
        }
        target.setStatus(source.getStatus());
        target.setAcceptedAt(source.getAcceptedAt());
        target.setStartedAt(source.getStartedAt());
        target.setFinishedAt(source.getFinishedAt());
        target.setRetryCount(source.getRetryCount());
        target.setErrorCode(source.getErrorCode());
        target.setErrorMessage(source.getErrorMessage());
        target.setUpdatedAt(source.getUpdatedAt());
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

    private void applyStatusResponse(AnalysisJob job, FastApiJobStatusResponse response, SchedulerLease lease) {
        if (response == null) {
            return;
        }
        writeJob(job, lease, current -> applyStatusResponseLocked(current, response), false);
    }

    private void applyStatusResponseLocked(AnalysisJob job, FastApiJobStatusResponse response) {
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
        if ("COMPLETED".equals(response.getStatus())) {
            analysisResultPersistService.persistFromGet(job.getId(), response);
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
        if (AnalysisJob.STATUS_COMPLETED.equals(job.getStatus())
                || AnalysisJob.STATUS_FAILED.equals(job.getStatus())) {
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
        if (isTerminalStatus(job)) {
            return;
        }
        if (AnalysisJob.ERROR_GET_NOT_FOUND_EXHAUSTED.equals(job.getErrorCode())) {
            return;
        }
        if (AnalysisJob.ERROR_GET_NOT_FOUND.equals(job.getErrorCode())) {
            job.setErrorCode(AnalysisJob.ERROR_GET_NOT_FOUND_EXHAUSTED);
            job.setErrorMessage("FastAPI job was not found on repeated GET");
            job.setUpdatedAt(LocalDateTime.now());
            analysisJobRepository.save(job);
            log.warn("GET not found exhausted; automatic recovery stopped");
            return;
        }
        job.setErrorCode(AnalysisJob.ERROR_GET_NOT_FOUND);
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
