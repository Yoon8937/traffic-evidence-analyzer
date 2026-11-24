package com.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;

@Component
public class InternalTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_INTERNAL_TOKEN = "X-Internal-Token";
    public static final String ROLE_INTERNAL = "ROLE_INTERNAL";

    private static final Logger log = LoggerFactory.getLogger(InternalTokenAuthenticationFilter.class);

    private final String expectedToken;

    public InternalTokenAuthenticationFilter(
            @Value("${app.spring.internal-token:}") String expectedToken
    ) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String provided = request.getHeader(HEADER_INTERNAL_TOKEN);
        if (tokenMatches(provided)) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            "internal",
                            null,
                            Collections.singletonList(new SimpleGrantedAuthority(ROLE_INTERNAL))
                    );
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } else {
            log.warn("internal callback authentication failed. uri={}", request.getRequestURI());
        }
        filterChain.doFilter(request, response);
    }

    private boolean tokenMatches(String provided) {
        if (expectedToken.isEmpty()) {
            return false;
        }
        byte[] expectedBytes = expectedToken.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = provided == null ? new byte[0] : provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }
}
