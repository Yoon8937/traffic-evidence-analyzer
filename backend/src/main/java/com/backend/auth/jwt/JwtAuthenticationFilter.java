package com.backend.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorization == null) {
            log.warn("JWT authentication failed. reason=MISSING_TOKEN, uri={}", request.getRequestURI());
        } else if (!authorization.startsWith(BEARER_PREFIX)) {
            log.warn("JWT authentication failed. reason=INVALID_AUTHORIZATION_HEADER, uri={}", request.getRequestURI());
        } else {
            String token = authorization.substring(BEARER_PREFIX.length());

            if (jwtTokenProvider.isValid(token)) {
                String userId = jwtTokenProvider.getUserId(token);

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                userId,
                                null,
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"))
                        );

                SecurityContextHolder.getContext().setAuthentication(authentication);

                log.debug("JWT authentication successful. userId={}, uri={}", userId, request.getRequestURI());
            } else {
                log.warn("JWT authentication failed. reason=INVALID_OR_EXPIRED_TOKEN, uri={}", request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }
}