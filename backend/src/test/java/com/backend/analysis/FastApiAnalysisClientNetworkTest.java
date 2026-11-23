package com.backend.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FastApiAnalysisClientNetworkTest {

    @Test
    void submit_timeout() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")));

        FastApiAnalysisClient client = new FastApiAnalysisClient(
                restTemplate,
                JsonMapper.builder().build(),
                "http://localhost:8000",
                "test-internal-token"
        );

        FastApiAcceptanceResult result = client.submit(UUID.randomUUID(), UUID.randomUUID(), "videos/key.MOV");

        assertEquals(FastApiAcceptanceResult.OUTCOME_TIMEOUT, result.getOutcome());
        assertEquals("FASTAPI_TIMEOUT", result.getErrorCode());
    }

    @Test
    void submit_connectionFailed() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        FastApiAnalysisClient client = new FastApiAnalysisClient(
                restTemplate,
                JsonMapper.builder().build(),
                "http://localhost:8000",
                "test-internal-token"
        );

        FastApiAcceptanceResult result = client.submit(UUID.randomUUID(), UUID.randomUUID(), "videos/key.MOV");

        assertEquals(FastApiAcceptanceResult.OUTCOME_CONNECTION_FAILED, result.getOutcome());
        assertEquals("FASTAPI_CONNECTION_FAILED", result.getErrorCode());
    }

    @Test
    void getJobStatus_timeout() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")));

        FastApiAnalysisClient client = new FastApiAnalysisClient(
                restTemplate,
                JsonMapper.builder().build(),
                "http://localhost:8000",
                "test-internal-token"
        );

        FastApiJobStatusResponse result = client.getJobStatus(UUID.randomUUID());

        assertEquals(FastApiJobStatusResponse.OUTCOME_TIMEOUT, result.getOutcome());
        assertEquals(0, result.getHttpStatus());
    }

    @Test
    void getJobStatus_connectionFailed() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        FastApiAnalysisClient client = new FastApiAnalysisClient(
                restTemplate,
                JsonMapper.builder().build(),
                "http://localhost:8000",
                "test-internal-token"
        );

        FastApiJobStatusResponse result = client.getJobStatus(UUID.randomUUID());

        assertEquals(FastApiJobStatusResponse.OUTCOME_CONNECTION_FAILED, result.getOutcome());
        assertEquals(0, result.getHttpStatus());
    }
}
