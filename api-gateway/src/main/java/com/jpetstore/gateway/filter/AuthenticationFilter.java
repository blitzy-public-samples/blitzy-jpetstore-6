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
import java.util.List;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * JWT Authentication GlobalFilter for the JPetStore API Gateway.
 *
 * <p>This filter replaces the monolith's session-scoped {@code AccountActionBean.authenticated}
 * flag (see {@code AccountActionBean.java} line 61: {@code private boolean authenticated;})
 * with stateless JWT-based authentication at the gateway edge.</p>
 *
 * <h3>Authentication Flow</h3>
 * <ol>
 *   <li>Extract JWT token from {@code Authorization: Bearer <token>} header or
 *       {@code jwt-token} HTTP-only cookie</li>
 *   <li>For protected paths: validate token, return 401 if invalid or missing</li>
 *   <li>For public paths: pass through without error if token is absent or invalid</li>
 *   <li>On valid token: extract {@code username} (subject) and {@code accountId} (custom claim),
 *       propagate as {@code X-Auth-Username} and {@code X-Auth-AccountId} headers to downstream services</li>
 * </ol>
 *
 * <h3>Protected vs Public Path Classification</h3>
 * <p>Protected paths require valid JWT authentication, matching the monolith's behavior:</p>
 * <ul>
 *   <li>{@code /actions/Order.action} — per {@code OrderActionBean.newOrderForm()} line 125:
 *       {@code if (accountBean == null || !accountBean.isAuthenticated())}</li>
 *   <li>{@code /api/orders/} — all REST API order operations</li>
 * </ul>
 * <p>Public paths pass through without JWT (catalog browsing, signon, cart, static assets, actuator).</p>
 *
 * <h3>Filter Ordering</h3>
 * <p>Executes at order {@code -100}, BEFORE {@code RoutingFlagFilter} (order 0),
 * ensuring JWT validation and claim propagation occur before routing decisions.</p>
 *
 * <h3>JJWT 0.12.6 API</h3>
 * <p>Uses the current JJWT 0.12.6 API: {@code Jwts.parser().verifyWith(key).requireIssuer(issuer)
 * .build().parseSignedClaims(token).getPayload()}</p>
 *
 * <h3>Reactive Execution</h3>
 * <p>Fully non-blocking. Returns {@code Mono<Void>} throughout. No servlet imports,
 * no blocking calls. Runs on Spring Cloud Gateway's Netty event loop.</p>
 *
 * @see org.springframework.cloud.gateway.filter.GlobalFilter
 * @see org.springframework.core.Ordered
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    // -------------------------------------------------------------------------
    // Path Classification Constants
    // -------------------------------------------------------------------------

    /**
     * URL path prefixes that require a valid JWT token.
     *
     * <p>Derived from the monolith's authentication checks:</p>
     * <ul>
     *   <li>{@code /actions/Order.action} — OrderActionBean.newOrderForm() (line 125),
     *       listOrders() (line 109), viewOrder() (line 174) all read the authenticated
     *       AccountActionBean from session</li>
     *   <li>{@code /api/orders/} — all REST API order operations require authenticated user</li>
     * </ul>
     *
     * <p>All other paths are public:</p>
     * <ul>
     *   <li>{@code /actions/Catalog.action} — CatalogActionBean has zero auth checks</li>
     *   <li>{@code /actions/Account.action} — signon/registration are public; editAccount auth
     *       is deferred to the monolith during coexistence</li>
     *   <li>{@code /actions/Cart.action} — cart is public for unauthenticated users</li>
     *   <li>{@code /css/}, {@code /images/} — static assets</li>
     *   <li>{@code /api/accounts/signon} — authentication endpoint must be public</li>
     *   <li>{@code /actuator/} — health/info endpoints</li>
     * </ul>
     */
    private static final List<String> PROTECTED_PATH_PREFIXES = List.of(
            "/actions/Order.action",
            "/api/orders/"
    );

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
    // Configuration Properties (injected from application.yml)
    // -------------------------------------------------------------------------

    /**
     * HMAC-SHA signing key for JWT signature verification.
     * Injected from {@code jwt.secret} in application.yml.
     * The default value is for development/testing only — MUST be overridden
     * via {@code JWT_SECRET} environment variable in production.
     */
    @Value("${jwt.secret:changeme-in-production}")
    private String jwtSecret;

    /**
     * Expected issuer claim in the JWT token.
     * Tokens with a different issuer will be rejected.
     * Injected from {@code jwt.issuer} in application.yml.
     */
    @Value("${jwt.issuer:jpetstore}")
    private String jwtIssuer;

    // -------------------------------------------------------------------------
    // Ordered Interface Implementation
    // -------------------------------------------------------------------------

    /**
     * Returns the filter execution order.
     *
     * <p>Returns {@code -100} to execute BEFORE the {@code RoutingFlagFilter} (order 0),
     * ensuring JWT validation and claim propagation into downstream request headers
     * occur before any routing decisions are made by the Strangler Fig routing logic.</p>
     *
     * @return {@code -100} — high-priority execution order
     */
    @Override
    public int getOrder() {
        return -100;
    }

    // -------------------------------------------------------------------------
    // GlobalFilter Implementation
    // -------------------------------------------------------------------------

    /**
     * Core JWT validation filter logic.
     *
     * <p>Execution flow:</p>
     * <ol>
     *   <li>Extract the request path and determine if it is protected</li>
     *   <li>Attempt to extract a JWT token from the Authorization header or cookie</li>
     *   <li>If no token is found:
     *       <ul>
     *         <li>Protected path → return 401 Unauthorized</li>
     *         <li>Public path → pass through to the filter chain</li>
     *       </ul>
     *   </li>
     *   <li>If a token is found, validate it using JJWT 0.12.6:
     *       <ul>
     *         <li>Valid token → extract claims, propagate as headers, continue chain</li>
     *         <li>Invalid token on protected path → return 401 Unauthorized</li>
     *         <li>Invalid token on public path → pass through without claims</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param exchange the current server web exchange (reactive)
     * @param chain    the gateway filter chain for continuing downstream
     * @return {@code Mono<Void>} — fully reactive, non-blocking
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        boolean isProtected = isProtectedPath(path);

        // Attempt to extract JWT token from Authorization header or cookie
        String token = extractToken(exchange.getRequest());

        // No token found — decide based on path protection
        if (token == null) {
            if (isProtected) {
                log.debug("No JWT token found for protected path: {}", path);
                return onUnauthorized(exchange);
            }
            // Public path — pass through without authentication
            return chain.filter(exchange);
        }

        // Token found — validate and extract claims
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

            // JJWT 0.12.6 API: parser() → verifyWith() → requireIssuer() → build()
            //                   → parseSignedClaims() → getPayload()
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(jwtIssuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String username = claims.getSubject();
            String accountId = claims.get("accountId", String.class);

            log.debug("JWT validated successfully for user: {} on path: {}", username, path);

            // Propagate authenticated user claims as headers to downstream services.
            // This replaces the monolith's session-scoped AccountActionBean state:
            //   AccountActionBean.getAccount().getUsername() → X-Auth-Username header
            //   AccountActionBean.getAccount() identity   → X-Auth-AccountId header
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(AUTH_HEADER_USERNAME, username != null ? username : "")
                    .header(AUTH_HEADER_ACCOUNT_ID, accountId != null ? accountId : "")
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (JwtException e) {
            // JWT validation failed — expired, invalid signature, malformed, wrong issuer, etc.
            log.debug("JWT validation failed for path {}: {}", path, e.getMessage());

            if (isProtected) {
                return onUnauthorized(exchange);
            }

            // Public path with invalid token — pass through without claims.
            // This allows users with expired tokens to still browse public pages
            // without being forced to re-authenticate until they access protected resources.
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
     * Determines whether the given request path requires JWT authentication.
     *
     * <p>A path is considered protected if it starts with any of the prefixes
     * defined in {@link #PROTECTED_PATH_PREFIXES}. All other paths are public.</p>
     *
     * <p>Protected paths are derived from the monolith's authentication checks:</p>
     * <ul>
     *   <li>{@code /actions/Order.action} — OrderActionBean.newOrderForm() checks
     *       {@code accountBean.isAuthenticated()} at line 125</li>
     *   <li>{@code /api/orders/} — REST API order endpoints always require authentication</li>
     * </ul>
     *
     * @param path the request URI path
     * @return {@code true} if the path requires authentication, {@code false} otherwise
     */
    private boolean isProtectedPath(String path) {
        return PROTECTED_PATH_PREFIXES.stream()
                .anyMatch(path::startsWith);
    }

    /**
     * Sends a 401 Unauthorized response.
     *
     * <p>Sets the HTTP status code to {@code 401 Unauthorized} and completes the
     * response immediately without forwarding to downstream services. This mirrors
     * the monolith's behavior where unauthenticated users attempting to access
     * protected resources (e.g., OrderActionBean.newOrderForm()) are redirected
     * to the signon page with message "You must sign on before attempting to check out."</p>
     *
     * @param exchange the current server web exchange
     * @return {@code Mono<Void>} — completes the response
     */
    private Mono<Void> onUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }
}
