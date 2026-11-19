package com.backend.analysis;

import com.backend.video.Video;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AnalysisJobService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);

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

    public void dispatchToFastApi(AnalysisJob job, String objectKey) {
        putJobContext(job);
        MDC.put("status", job.getStatus());
        try {
            log.info("FastAPI analysis request started");
            FastApiAcceptanceResult result = fastApiAnalysisClient.submit(job.getId(), job.getVideoId(), objectKey);
            applyResult(job, result);
        } catch (RuntimeException e) {
            MDC.put("error_type", e.getClass().getSimpleName());
            log.error("FastAPI request failed");
            job.setStatus(AnalysisJob.STATUS_PENDING);
            job.setErrorCode("FASTAPI_REQUEST_FAILED");
            job.setErrorMessage("FastAPI request failed");
            analysisJobRepository.save(job);
        } finally {
            clearJobContext();
        }
    }

    private void applyResult(AnalysisJob job, FastApiAcceptanceResult result) {
        MDC.put("status", job.getStatus());
        if (result.isAccepted()) {
            job.setStatus(AnalysisJob.STATUS_PENDING);
            job.setAcceptedAt(result.getAcceptedAt());
            job.setErrorCode(null);
            job.setErrorMessage(null);
            analysisJobRepository.save(job);
            log.info("FastAPI job accepted");
            log.info("accepted_at updated");
            return;
        }

        MDC.put("error_type", result.getErrorCode());
        if (FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR.equals(result.getOutcome())) {
            job.setStatus(AnalysisJob.STATUS_FAILED);
            job.setErrorCode(result.getErrorCode());
            job.setErrorMessage(result.getErrorMessage());
            analysisJobRepository.save(job);
            log.error("FastAPI request failed");
            return;
        }

        job.setStatus(AnalysisJob.STATUS_PENDING);
        job.setErrorCode(result.getErrorCode());
        job.setErrorMessage(result.getErrorMessage());
        analysisJobRepository.save(job);
        if (FastApiAcceptanceResult.OUTCOME_TIMEOUT.equals(result.getOutcome())) {
            log.error("FastAPI request timed out");
        } else {
            log.error("FastAPI request failed");
        }
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
