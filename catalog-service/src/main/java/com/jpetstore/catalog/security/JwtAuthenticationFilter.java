/*
 * Copyright 2010-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jpetstore.catalog.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import javax.crypto.SecretKey;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defense-in-depth JWT authentication filter for the Catalog Service.
 *
 * <p>This filter validates JWT tokens on incoming requests to ensure that even if
 * the service port is directly accessible (bypassing the API Gateway), write
 * operations on protected endpoints are still authenticated. This addresses the
 * security finding where the inventory decrement endpoint was accessible without
 * any authentication when the Catalog Service port (8082) was directly reachable.</p>
 *
 * <p>The filter checks for a JWT token in the {@code Authorization: Bearer} header.
 * If a valid token is found, the authenticated username is placed into the
 * {@link SecurityContextHolder}. If no token is found or the token is invalid,
 * the request continues without authentication — Spring Security's authorization
 * rules determine whether to reject it (e.g., inventory write endpoints require auth,
 * while read-only catalog browsing remains public).</p>
 *
 * @author Blitzy Platform
 * @see org.springframework.web.filter.OncePerRequestFilter
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final SecretKey secretKey;
    private final String jwtIssuer;

    /**
     * Constructs the filter with JWT signing key and issuer for token validation.
     *
     * @param jwtSecret raw HMAC-SHA secret string (must match Account Service and Gateway)
     * @param jwtIssuer expected issuer claim in the JWT token
     */
    public JwtAuthenticationFilter(String jwtSecret, String jwtIssuer) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.jwtIssuer = jwtIssuer;
        log.info("Catalog Service JWT authentication filter initialized for defense-in-depth");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();

            if (!token.isEmpty()) {
                try {
                    Claims claims = Jwts.parser()
                            .verifyWith(secretKey)
                            .requireIssuer(jwtIssuer)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();

                    String username = claims.getSubject();

                    if (username != null && !username.isEmpty()) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        username, null, Collections.emptyList());
                        SecurityContextHolder.getContext().setAuthentication(authentication);

                        log.debug("JWT authenticated user '{}' for path: {}",
                                username, request.getRequestURI());
                    }
                } catch (JwtException e) {
                    log.debug("JWT validation failed for path {}: {}",
                            request.getRequestURI(), e.getMessage());
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
