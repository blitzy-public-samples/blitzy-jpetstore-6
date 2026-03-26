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
package com.jpetstore.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * JWT Authentication WebFilter for the JPetStore API Gateway.
 *
 * <p>This filter replaces the monolith's session-scoped {@code AccountActionBean.authenticated}
 * flag (see {@code AccountActionBean.java} line 61: {@code private boolean authenticated;})
 * with stateless JWT-based authentication at the gateway edge.</p>
 *
 * <h3>Authentication Flow</h3>
 * <ol>
 *   <li>Extract JWT token from {@code Authorization: Bearer <token>} header or
 *       {@code jwt-token} HTTP-only cookie</li>
 *   <li>If a valid token is found: extract claims, populate Spring Security's
 *       {@code ReactiveSecurityContext}, and propagate claims as downstream headers</li>
 *   <li>If no token or invalid token: pass through without populating SecurityContext.
 *       Spring Security's authorization rules (in SecurityConfig) will return 401
 *       for protected paths and permit access to public paths.</li>
 * </ol>
 *
 * <h3>WebFilter vs GlobalFilter</h3>
 * <p>This filter is registered as a {@link WebFilter} (NOT a {@code GlobalFilter}) and
 * is placed in the Spring Security filter chain via
 * {@code SecurityConfig.addFilterBefore(..., SecurityWebFiltersOrder.AUTHENTICATION)}.
 * This ensures JWT validation runs in the SAME filter chain as Spring Security's
 * {@code AuthorizationWebFilter}, so the populated {@code SecurityContext} is visible
 * to the authorization check. A {@code GlobalFilter} would run in a separate chain
 * AFTER the Security WebFilter chain, making the SecurityContext unavailable to
 * the authorization check — which was the root cause of the original 401 bug.</p>
 *
 * <h3>Claim Propagation</h3>
 * <p>On valid JWT, the filter adds {@code X-Auth-Username} and {@code X-Auth-AccountId}
 * headers to the downstream request, enabling backend microservices to identify the
 * authenticated user without re-validating the JWT.</p>
 *
 * <h3>JJWT 0.12.6 API</h3>
 * <p>Uses the current JJWT 0.12.6 API: {@code Jwts.parser().verifyWith(key).requireIssuer(issuer)
 * .build().parseSignedClaims(token).getPayload()}</p>
 *
 * <h3>Reactive Execution</h3>
 * <p>Fully non-blocking. Returns {@code Mono<Void>} throughout. No servlet imports,
 * no blocking calls. Runs on Spring Cloud Gateway's Netty event loop.</p>
 *
 * @see org.springframework.web.server.WebFilter
 * @see org.springframework.security.web.server.SecurityWebFilterChain
 */
public class AuthenticationFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    // -------------------------------------------------------------------------
    // Downstream Header Constants
    // -------------------------------------------------------------------------

    /**
     * Header name for propagating the authenticated username to downstream services.
     * Downstream services can read this header to identify the authenticated user
     * without re-validating the JWT token.
     */
    private static final String AUTH_HEADER_USERNAME = "X-Auth-Username";

    /**
     * Header name for propagating the account ID to downstream services.
     * This is a custom claim extracted from the JWT payload.
     */
    private static final String AUTH_HEADER_ACCOUNT_ID = "X-Auth-AccountId";

    /**
     * Prefix for the Bearer token scheme in the Authorization header.
     * Per RFC 6750, the format is: {@code Authorization: Bearer <token>}
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Name of the HTTP-only cookie that may contain the JWT token.
     * Per AAP Section 0.7.2, the JWT is stored as an HTTP-only cookie
     * to support the monolith's JSP-based UI without requiring JavaScript
     * to attach the token to every request.
     */
    private static final String JWT_COOKIE_NAME = "jwt-token";

    // -------------------------------------------------------------------------
    // Configuration (injected via constructor from SecurityConfig)
    // -------------------------------------------------------------------------

    /**
     * Redis key prefix for per-user password-change timestamps.
     * Must match the prefix used by Account Service when storing timestamps.
     */
    private static final String PWD_CHANGED_KEY_PREFIX = "jwt:pwd_changed:";

    /** HMAC-SHA signing key for JWT signature verification. */
    private final SecretKey secretKey;

    /** Expected issuer claim in the JWT token. */
    private final String jwtIssuer;

    /** Reactive Redis template for checking per-user token revocation timestamps. */
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    /**
     * Constructs the AuthenticationFilter with JWT configuration and Redis access
     * for token revocation checking.
     *
     * <p>The SecretKey is derived from the raw secret string once at construction
     * time to avoid per-request key construction overhead in a gateway that
     * processes all traffic.</p>
     *
     * @param jwtSecret     raw HMAC-SHA signing key string (from {@code jwt.secret} config)
     * @param jwtIssuer     expected issuer claim (from {@code jwt.issuer} config)
     * @param redisTemplate reactive Redis template for token revocation checks
     */
    public AuthenticationFilter(String jwtSecret, String jwtIssuer,
                                ReactiveRedisTemplate<String, String> redisTemplate) {
        this.secretKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.jwtIssuer = jwtIssuer;
        this.redisTemplate = redisTemplate;
        log.info("JWT AuthenticationFilter initialized (WebFilter mode, registered in Security chain)");
    }

    // -------------------------------------------------------------------------
    // WebFilter Implementation
    // -------------------------------------------------------------------------

    /**
     * Core JWT validation filter logic.
     *
     * <p>Execution flow:</p>
     * <ol>
     *   <li>Attempt to extract a JWT token from the Authorization header or cookie</li>
     *   <li>If no token is found: pass through — Spring Security handles 401 for protected paths</li>
     *   <li>If a token is found, validate it using JJWT 0.12.6:
     *       <ul>
     *         <li>Valid token → populate SecurityContext, propagate claims as headers, continue chain</li>
     *         <li>Invalid token → log warning, pass through without SecurityContext.
     *             Spring Security handles 401 for protected paths, public paths pass through.</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p><strong>Key design decision:</strong> This filter NEVER returns 401 directly.
     * Authorization (which paths require authentication) is solely the responsibility of
     * {@code SecurityConfig}'s authorization rules. This filter only populates the
     * SecurityContext — it does not enforce access control.</p>
     *
     * @param exchange the current server web exchange (reactive)
     * @param chain    the WebFilter chain for continuing downstream
     * @return {@code Mono<Void>} — fully reactive, non-blocking
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // Attempt to extract JWT token from Authorization header or cookie
        String token = extractToken(exchange.getRequest());

        // No token found — pass through. Spring Security's authorization rules
        // will return 401 for protected paths and permit access for public paths.
        if (token == null) {
            return chain.filter(exchange);
        }

        // Token found — validate and extract claims
        try {
            // Use the cached SecretKey (initialized once in constructor) to avoid
            // per-request key construction overhead in a gateway processing all traffic.
            // JJWT 0.12.6 API: parser() → verifyWith() → requireIssuer() → build()
            //                   → parseSignedClaims() → getPayload()
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(jwtIssuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String username = claims.getSubject();
            String accountId = claims.get("accountId", String.class);
            Date issuedAt = claims.getIssuedAt();

            log.debug("JWT validated successfully for user: {} on path: {}", username, path);

            // Check Redis for per-user password-change timestamp to support
            // JWT revocation after password changes (Issue 11 fix).
            // If the token was issued before the user's password was changed,
            // treat the token as revoked — do not populate SecurityContext.
            return checkTokenRevocation(username, issuedAt)
                    .flatMap(revoked -> {
                        if (revoked) {
                            log.debug("JWT revoked for user {} — token issued before password change", username);
                            return chain.filter(exchange);
                        }

                        // Token is valid and not revoked — propagate claims downstream.
                        // This replaces the monolith's session-scoped AccountActionBean state:
                        //   AccountActionBean.getAccount().getUsername() → X-Auth-Username header
                        //   AccountActionBean.getAccount() identity   → X-Auth-AccountId header
                        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                .header(AUTH_HEADER_USERNAME, username != null ? username : "")
                                .header(AUTH_HEADER_ACCOUNT_ID, accountId != null ? accountId : "")
                                .build();

                        // Populate the ReactiveSecurityContext so that Spring Security's
                        // .authenticated() check on protected paths succeeds.
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(
                                        username,
                                        null,
                                        Collections.emptyList()
                                );
                        SecurityContextImpl securityContext = new SecurityContextImpl(authentication);

                        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                                .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                                        Mono.just(securityContext)
                                ));
                    });

        } catch (JwtException e) {
            // JWT validation failed — expired, invalid signature, malformed, wrong issuer, etc.
            // Do NOT return 401 here — let Spring Security handle authorization.
            // This allows users with expired/invalid tokens to still browse public pages
            // without being forced to re-authenticate until they access protected resources.
            log.debug("JWT validation failed for path {}: {}", path, e.getMessage());
            return chain.filter(exchange);
        }
    }

    // -------------------------------------------------------------------------
    // Private Helper Methods
    // -------------------------------------------------------------------------

    /**
     * Extracts the JWT token from the incoming request.
     *
     * <p>Token extraction priority:</p>
     * <ol>
     *   <li>{@code Authorization: Bearer <token>} header — standard REST API clients</li>
     *   <li>{@code jwt-token} HTTP-only cookie — browser-based monolith JSP pages
     *       (per AAP Section 0.7.2)</li>
     * </ol>
     *
     * @param request the incoming server HTTP request (reactive)
     * @return the JWT token string, or {@code null} if no token is present
     */
    private String extractToken(ServerHttpRequest request) {
        // 1. Check Authorization header for Bearer token (RFC 6750)
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }

        // 2. Check HTTP-only cookie (browser-based access via monolith JSP pages)
        HttpCookie cookie = request.getCookies().getFirst(JWT_COOKIE_NAME);
        if (cookie != null) {
            String cookieValue = cookie.getValue();
            if (cookieValue != null && !cookieValue.isEmpty()) {
                return cookieValue;
            }
        }

        return null;
    }

    /**
     * Checks whether a JWT token has been effectively revoked due to a password change.
     *
     * <p>When a user changes their password, the Account Service stores the password-change
     * timestamp in Redis under the key {@code jwt:pwd_changed:{username}}. This method
     * checks that timestamp against the token's {@code iat} (issued-at) claim. If the
     * token was issued before the password change, it is considered revoked.</p>
     *
     * <p>Graceful degradation: if Redis is unavailable or the lookup fails, the token
     * is treated as NOT revoked (fail-open). This ensures that a Redis outage does not
     * prevent all authenticated users from accessing the application.</p>
     *
     * @param username the JWT subject (username)
     * @param issuedAt the JWT's issued-at date
     * @return {@code Mono<Boolean>} — {@code true} if the token is revoked, {@code false} otherwise
     */
    private Mono<Boolean> checkTokenRevocation(String username, Date issuedAt) {
        // If Redis template is not configured or username/issuedAt is missing,
        // fail-open: treat the token as not revoked.
        if (redisTemplate == null || username == null || issuedAt == null) {
            return Mono.just(Boolean.FALSE);
        }

        String redisKey = PWD_CHANGED_KEY_PREFIX + username;
        return redisTemplate.opsForValue().get(redisKey)
                .map(timestampStr -> {
                    try {
                        long pwdChangedMillis = Long.parseLong(timestampStr);
                        long tokenIssuedMillis = issuedAt.getTime();
                        // Token is revoked if it was issued before the password change.
                        // A 1-second buffer accounts for clock skew between services.
                        boolean revoked = tokenIssuedMillis < (pwdChangedMillis - 1000);
                        if (revoked) {
                            log.debug("Token revocation check: user={}, tokenIat={}, pwdChanged={} — REVOKED",
                                    username, tokenIssuedMillis, pwdChangedMillis);
                        }
                        return revoked;
                    } catch (NumberFormatException e) {
                        log.warn("Invalid password-change timestamp in Redis for user {}: {}", username, timestampStr);
                        return Boolean.FALSE;
                    }
                })
                // No entry in Redis means no recent password change — token is valid.
                .defaultIfEmpty(Boolean.FALSE)
                // Redis errors should not break authentication — fail-open with a warning log.
                .onErrorResume(ex -> {
                    log.warn("Redis error during token revocation check for user {}: {}", username, ex.getMessage());
                    return Mono.just(Boolean.FALSE);
                });
    }
}
