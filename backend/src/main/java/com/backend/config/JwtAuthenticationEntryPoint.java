package com.backend.config;

import com.backend.auth.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationEntryPoint.class);
    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        log.warn("Authentication failed. method={}, uri={}, exception={}, message={}",
                request.getMethod(),
                request.getRequestURI(),
                authException.getClass().getSimpleName(),
                authException.getMessage()
        );

        objectMapper.writeValue(
                response.getWriter(),
                new ErrorResponse("UNAUTHORIZED", "인증이 필요합니다.")
        );
    }
}
