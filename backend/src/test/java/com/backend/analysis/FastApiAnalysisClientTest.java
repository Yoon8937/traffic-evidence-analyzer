package com.backend.analysis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class FastApiAnalysisClientTest {

    private MockRestServiceServer server;
    private FastApiAnalysisClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new FastApiAnalysisClient(restTemplate, JsonMapper.builder().build(), "http://localhost:8000");
    }

    @Test
    void submit_accepted202_usesAcceptedAtFromBody() {
        UUID jobId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID videoId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"job_id\":\"550e8400-e29b-41d4-a716-446655440000\","
                                + "\"video_id\":\"11111111-1111-1111-1111-111111111111\","
                                + "\"object_key\":\"videos/2026/09/28/" + videoId + ".MOV\"}"
                ))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"accepted_at\":\"2026-11-19T04:20:00Z\"}"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/2026/09/28/" + videoId + ".MOV");

        server.verify();
        assertTrue(result.isAccepted());
        assertEquals(202, result.getHttpStatus());
        assertEquals(2026, result.getAcceptedAt().getYear());
        assertNull(result.getErrorCode());
    }

    @Test
    void submit_http409_isTreatedAsAccepted() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/key.MOV");

        assertTrue(result.isAccepted());
        assertNotNull(result.getAcceptedAt());
    }

    @Test
    void submit_http400_isClientError() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("bad request"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/key.MOV");

        assertEquals(FastApiAcceptanceResult.OUTCOME_CLIENT_ERROR, result.getOutcome());
        assertEquals("FASTAPI_CLIENT_ERROR", result.getErrorCode());
    }

    @Test
    void submit_http500_isServerError() {
        UUID jobId = UUID.randomUUID();
        UUID videoId = UUID.randomUUID();

        server.expect(requestTo("http://localhost:8000/internal/analysis-jobs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("error"));

        FastApiAcceptanceResult result = client.submit(jobId, videoId, "videos/key.MOV");

        assertEquals(FastApiAcceptanceResult.OUTCOME_SERVER_ERROR, result.getOutcome());
        assertEquals("FASTAPI_SERVER_ERROR", result.getErrorCode());
    }
}
