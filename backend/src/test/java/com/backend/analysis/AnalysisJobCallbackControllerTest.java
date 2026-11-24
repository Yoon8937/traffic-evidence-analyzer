package com.backend.analysis;

import com.backend.auth.jwt.JwtAuthenticationFilter;
import com.backend.auth.jwt.JwtTokenProvider;
import com.backend.config.InternalTokenAuthenticationFilter;
import com.backend.config.JwtAuthenticationEntryPoint;
import com.backend.config.SecurityConfig;
import com.backend.exception.CallbackConflictException;
import com.backend.exception.CallbackValidationException;
import com.backend.exception.GlobalExceptionHandler;
import com.backend.exception.JobNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalysisJobCallbackController.class)
@Import({
        SecurityConfig.class,
        InternalTokenAuthenticationFilter.class,
        JwtAuthenticationFilter.class,
        JwtTokenProvider.class,
        JwtAuthenticationEntryPoint.class,
        GlobalExceptionHandler.class
})
@TestPropertySource(properties = {
        "app.spring.internal-token=spring-callback-token",
        "app.jwt.secret=local-dev-only-change-this-secret-key-32b",
        "app.jwt.expiration-ms=3600000"
})
class AnalysisJobCallbackControllerTest {

    private static final String TOKEN = "spring-callback-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalysisJobCallbackService analysisJobCallbackService;

    @MockitoBean
    private AnalysisResultCallbackService analysisResultCallbackService;

    @Test
    void validTokenReturns204() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/status")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingJson(jobId)))
                .andExpect(status().isNoContent());
        verify(analysisJobCallbackService).handleStatus(any(), any());
    }

    @Test
    void missingTokenReturns401() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingJson(jobId)))
                .andExpect(status().isUnauthorized());
        verify(analysisJobCallbackService, never()).handleStatus(any(), any());
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/status")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingJson(jobId)))
                .andExpect(status().isUnauthorized());
        verify(analysisJobCallbackService, never()).handleStatus(any(), any());
    }

    @Test
    void internalTokenDoesNotOpenUserApis() throws Exception {
        mockMvc.perform(post("/api/videos")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRemainsPermitAll() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"user\",\"password\":\"pass\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownJobReturns404() throws Exception {
        UUID jobId = UUID.randomUUID();
        doThrow(new JobNotFoundException("analysis job not found"))
                .when(analysisJobCallbackService).handleStatus(any(), any());
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/status")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingJson(jobId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidJsonReturns400() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/status")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
        verify(analysisJobCallbackService, never()).handleStatus(any(), any());
    }

    @Test
    void jobIdMismatchReturns400() throws Exception {
        UUID pathJobId = UUID.randomUUID();
        doThrow(new CallbackValidationException("JOB_ID_MISMATCH", "URL job_id와 본문 job_id가 일치하지 않습니다."))
                .when(analysisJobCallbackService).handleStatus(any(), any());
        mockMvc.perform(post("/internal/analysis-jobs/" + pathJobId + "/status")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingJson(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void jwtDoesNotAuthorizeInternalApi() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/status")
                        .header("Authorization", "Bearer not-an-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingJson(jobId)))
                .andExpect(status().isUnauthorized());
        verify(analysisJobCallbackService, never()).handleStatus(any(), any());
    }

    @Test
    void saveFailureReturns500() throws Exception {
        UUID jobId = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("db"))
                .when(analysisJobCallbackService).handleStatus(any(), any());
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/status")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(processingJson(jobId)))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void validTokenOnResultReturns204() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/result")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedJson(jobId)))
                .andExpect(status().isNoContent());
        verify(analysisResultCallbackService).handleResult(any(), any());
    }

    @Test
    void missingTokenOnResultReturns401() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedJson(jobId)))
                .andExpect(status().isUnauthorized());
        verify(analysisResultCallbackService, never()).handleResult(any(), any());
    }

    @Test
    void wrongTokenOnResultReturns401() throws Exception {
        UUID jobId = UUID.randomUUID();
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/result")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedJson(jobId)))
                .andExpect(status().isUnauthorized());
        verify(analysisResultCallbackService, never()).handleResult(any(), any());
    }

    @Test
    void resultConflictReturns409() throws Exception {
        UUID jobId = UUID.randomUUID();
        doThrow(new CallbackConflictException("RESULT_CONFLICT", "이미 저장된 분석 결과와 다른 결과가 수신되었습니다."))
                .when(analysisResultCallbackService).handleResult(any(), any());
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/result")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedJson(jobId)))
                .andExpect(status().isConflict());
    }

    @Test
    void resultSaveFailureReturns500() throws Exception {
        UUID jobId = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("db"))
                .when(analysisResultCallbackService).handleResult(any(), any());
        mockMvc.perform(post("/internal/analysis-jobs/" + jobId + "/result")
                        .header(InternalTokenAuthenticationFilter.HEADER_INTERNAL_TOKEN, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completedJson(jobId)))
                .andExpect(status().isInternalServerError());
    }

    private String processingJson(UUID jobId) {
        return "{\"job_id\":\"" + jobId + "\",\"status\":\"PROCESSING\","
                + "\"started_at\":\"2026-11-24T04:00:00Z\"}";
    }

    private String completedJson(UUID jobId) {
        return "{\"job_id\":\"" + jobId + "\",\"status\":\"COMPLETED\","
                + "\"finished_at\":\"2026-11-24T04:05:00Z\",\"is_valid\":true,"
                + "\"summary\":{\"frames\":1000,\"track_count\":10,\"crossed_count\":3,"
                + "\"violation_count\":2,\"fps\":30.0,\"processing_ms\":15000},"
                + "\"events\":[{\"event_type\":\"VIOLATION\",\"frame\":120,\"track_id\":3,"
                + "\"evidence_s3_key\":\"results/example.jpg\"}],\"result_video_s3_key\":null}";
    }
}
