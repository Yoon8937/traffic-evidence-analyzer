package com.backend.analysis;

import com.backend.analysis.dto.AnalysisJobStatusCallbackRequest;
import com.backend.exception.CallbackValidationException;
import com.backend.exception.JobNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AnalysisJobCallbackService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobCallbackService.class);

    private final AnalysisJobRepository analysisJobRepository;

    public AnalysisJobCallbackService(AnalysisJobRepository analysisJobRepository) {
        this.analysisJobRepository = analysisJobRepository;
    }

    @Transactional
    public void handleStatus(UUID pathJobId, AnalysisJobStatusCallbackRequest request) {
        validateRequest(pathJobId, request);
        AnalysisJob job = analysisJobRepository.findById(pathJobId)
                .orElseThrow(() -> new JobNotFoundException("analysis job not found"));

        putJobContext(job);
        MDC.put("status", job.getStatus());
        try {
            log.info("analysis status callback received. callbackStatus={}", request.getStatus());
            if (AnalysisJob.STATUS_PROCESSING.equals(request.getStatus())) {
                applyProcessing(job, request);
            } else {
                applyFailed(job, request);
            }
        } finally {
            MDC.remove("job_id");
            MDC.remove("video_id");
            MDC.remove("status");
            MDC.remove("error_type");
        }
    }

    private void applyProcessing(AnalysisJob job, AnalysisJobStatusCallbackRequest request) {
        LocalDateTime startedAt = parseRequiredUtc(request.getStartedAt(), "started_at");
        if (AnalysisJob.STATUS_COMPLETED.equals(job.getStatus())
                || AnalysisJob.STATUS_FAILED.equals(job.getStatus())) {
            log.warn("ignored PROCESSING callback because job is already {}", job.getStatus());
            return;
        }
        if (AnalysisJob.STATUS_PROCESSING.equals(job.getStatus())) {
            log.info("duplicate PROCESSING callback");
            if (job.getStartedAt() == null) {
                job.setStartedAt(startedAt);
                analysisJobRepository.save(job);
            }
            return;
        }
        job.setStatus(AnalysisJob.STATUS_PROCESSING);
        job.setStartedAt(startedAt);
        analysisJobRepository.save(job);
        MDC.put("status", AnalysisJob.STATUS_PROCESSING);
        log.info("job status changed to PROCESSING");
    }

    private void applyFailed(AnalysisJob job, AnalysisJobStatusCallbackRequest request) {
        LocalDateTime finishedAt = parseOptionalUtc(request.getFinishedAt(), "finished_at");
        if (AnalysisJob.STATUS_COMPLETED.equals(job.getStatus())) {
            log.warn("ignored FAILED callback because job is already COMPLETED");
            return;
        }
        if (AnalysisJob.STATUS_FAILED.equals(job.getStatus())) {
            log.info("duplicate FAILED callback");
            boolean changed = copyFailedFieldsIfPresent(job, request, finishedAt);
            if (changed) {
                analysisJobRepository.save(job);
            }
            return;
        }
        job.setStatus(AnalysisJob.STATUS_FAILED);
        copyFailedFieldsIfPresent(job, request, finishedAt);
        analysisJobRepository.save(job);
        MDC.put("status", AnalysisJob.STATUS_FAILED);
        log.info("job status changed to FAILED");
    }

    private boolean copyFailedFieldsIfPresent(
            AnalysisJob job,
            AnalysisJobStatusCallbackRequest request,
            LocalDateTime finishedAt
    ) {
        boolean changed = false;
        if (request.getErrorCode() != null) {
            job.setErrorCode(request.getErrorCode());
            changed = true;
        }
        if (request.getErrorMessage() != null) {
            job.setErrorMessage(request.getErrorMessage());
            changed = true;
        }
        if (finishedAt != null) {
            job.setFinishedAt(finishedAt);
            changed = true;
        }
        return changed;
    }

    private void validateRequest(UUID pathJobId, AnalysisJobStatusCallbackRequest request) {
        if (request == null) {
            throw new CallbackValidationException("VALIDATION_FAILED", "요청 본문이 필요합니다.");
        }
        if (request.getJobId() == null) {
            throw new CallbackValidationException("VALIDATION_FAILED", "job_id는 필수입니다.");
        }
        if (!request.getJobId().equals(pathJobId)) {
            throw new CallbackValidationException("JOB_ID_MISMATCH", "URL job_id와 본문 job_id가 일치하지 않습니다.");
        }
        if (request.getStatus() == null || request.getStatus().isBlank()) {
            throw new CallbackValidationException("VALIDATION_FAILED", "status는 필수입니다.");
        }
        if (!AnalysisJob.STATUS_PROCESSING.equals(request.getStatus())
                && !AnalysisJob.STATUS_FAILED.equals(request.getStatus())) {
            throw new CallbackValidationException("UNSUPPORTED_STATUS", "지원하지 않는 status입니다.");
        }
    }

    private LocalDateTime parseRequiredUtc(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new CallbackValidationException("VALIDATION_FAILED", field + "는 필수입니다.");
        }
        LocalDateTime parsed = UtcDateTimes.parse(raw);
        if (parsed == null) {
            throw new CallbackValidationException("INVALID_TIMESTAMP", field + " 형식이 올바르지 않습니다.");
        }
        return parsed;
    }

    private LocalDateTime parseOptionalUtc(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalDateTime parsed = UtcDateTimes.parse(raw);
        if (parsed == null) {
            throw new CallbackValidationException("INVALID_TIMESTAMP", field + " 형식이 올바르지 않습니다.");
        }
        return parsed;
    }

    private void putJobContext(AnalysisJob job) {
        if (job.getId() != null) {
            MDC.put("job_id", job.getId().toString());
        }
        if (job.getVideoId() != null) {
            MDC.put("video_id", job.getVideoId().toString());
        }
    }
}
