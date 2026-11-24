package com.backend.analysis;

import com.backend.analysis.dto.AnalysisJobStatusCallbackRequest;
import com.backend.auth.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@RestController
@RequestMapping("/internal/analysis-jobs")
public class AnalysisJobCallbackController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobCallbackController.class);

    private final AnalysisJobCallbackService analysisJobCallbackService;
    private final AnalysisResultCallbackService analysisResultCallbackService;

    public AnalysisJobCallbackController(
            AnalysisJobCallbackService analysisJobCallbackService,
            AnalysisResultCallbackService analysisResultCallbackService
    ) {
        this.analysisJobCallbackService = analysisJobCallbackService;
        this.analysisResultCallbackService = analysisResultCallbackService;
    }

    @PostMapping("/{jobId}/status")
    public ResponseEntity<Void> updateStatus(
            @PathVariable UUID jobId,
            @RequestBody AnalysisJobStatusCallbackRequest request
    ) {
        MDC.put("job_id", jobId.toString());
        log.info("analysis status callback request accepted");
        analysisJobCallbackService.handleStatus(jobId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{jobId}/result")
    public ResponseEntity<Void> saveResult(
            @PathVariable UUID jobId,
            @RequestBody JsonNode payload
    ) {
        MDC.put("job_id", jobId.toString());
        log.info("analysis result callback request accepted");
        analysisResultCallbackService.handleResult(jobId, payload);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException ex) {
        log.error("callback persistence failed");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("CALLBACK_SAVE_FAILED", "상태 저장에 실패했습니다."));
    }
}
