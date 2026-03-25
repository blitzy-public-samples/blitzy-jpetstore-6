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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link AuthenticationFilter} — the JWT validation WebFilter in the
 * JPetStore API Gateway.
 *
 * <p>This filter replaces the monolith's session-scoped
 * {@code AccountActionBean.authenticated} flag (see {@code AccountActionBean.java}
 * line 61, lines 195-197) with stateless JWT-based authentication at the gateway edge.</p>
 *
 * <h3>Design Note: WebFilter vs GlobalFilter</h3>
 * <p>{@link AuthenticationFilter} implements {@link WebFilter} (NOT {@code GlobalFilter})
 * and is registered in the Spring Security filter chain via
 * {@code SecurityConfig.addFilterBefore(SecurityWebFiltersOrder.AUTHENTICATION)}.
 * This ensures JWT validation and SecurityContext population happen within the same
 * filter chain as Spring Security's {@code AuthorizationWebFilter}.</p>
 *
 * <h3>Design Note: 401 Enforcement</h3>
 * <p>The filter itself NEVER returns 401 directly. On invalid/expired/missing tokens,
 * it passes through without populating the {@code ReactiveSecurityContext}. Spring
 * Security's authorization rules (in {@code SecurityConfig}) then return 401 for
 * protected paths and permit access for public paths. Test method names that mention
 * "shouldReturn401" document the expected end-to-end behavior — not the filter's
 * direct response.</p>
 *
 * <p>Test patterns follow the existing monolith test conventions:</p>
 * <ul>
 *   <li>Package-private class (no {@code public} modifier) — matches
 *       {@code AccountActionBeanTest}, {@code AccountServiceTest}</li>
 *   <li>Given/When/Then comment style — matches {@code AccountServiceTest}</li>
 *   <li>AssertJ {@code assertThat()} — matches all existing tests</li>
 *   <li>{@code @ExtendWith(MockitoExtension.class)} — matches {@code AccountServiceTest}</li>
 * </ul>
 *
 * @see AuthenticationFilter
 * @see com.jpetstore.gateway.config.SecurityConfig
 */
@ExtendWith(MockitoExtension.class)
class AuthenticationFilterTest {

    // -------------------------------------------------------------------------
    // Test Constants
    // -------------------------------------------------------------------------

    /**
     * HMAC-SHA256 secret key for signing test JWT tokens.
     * Must be at least 256 bits (32 bytes) for HMAC-SHA256.
     */
    private static final String JWT_SECRET =
            "test-secret-key-for-jwt-signing-that-is-long-enough-for-hmac-sha256";

    /**
     * Expected issuer claim in JWT tokens, matching the filter's configuration.
     */
    private static final String JWT_ISSUER = "jpetstore";

    // -------------------------------------------------------------------------
    // Mocks and Subject Under Test
    // -------------------------------------------------------------------------

    /**
     * Mock WebFilterChain — simulates the downstream filter chain.
     * Returns {@code Mono.empty()} to indicate successful downstream processing.
     */
    @Mock
    private WebFilterChain filterChain;

    /**
     * The AuthenticationFilter instance under test, constructed directly with
     * the test JWT secret and issuer values (constructor injection, not @Value).
     */
    private AuthenticationFilter authenticationFilter;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    /**
     * Initializes the AuthenticationFilter with test configuration and configures
     * the mock filter chain to return {@code Mono.empty()} for any exchange.
     */
    @BeforeEach
    void setUp() {
        // Construct the filter directly — it uses constructor injection
        // (not @Value fields), taking jwtSecret and jwtIssuer as parameters
        authenticationFilter = new AuthenticationFilter(JWT_SECRET, JWT_ISSUER);

        // Configure mock chain to simulate successful downstream processing
        when(filterChain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
    }

    // =========================================================================
    // 3a. Valid JWT Token Tests
    // =========================================================================

    /**
     * Verifies that a valid JWT token with username and accountId claims results in
     * {@code X-Auth-Username} and {@code X-Auth-AccountId} headers being propagated
     * to the downstream request via the mutated exchange.
     *
     * <p>This replaces the monolith's session-scoped state:
     * {@code AccountActionBean.getAccount().getUsername()} → X-Auth-Username header,
     * {@code AccountActionBean.getAccount()} identity → X-Auth-AccountId header.</p>
     */
    @Test
    void validJwtToken_shouldPropagateClaimsAsHeaders() {
        // given
        String token = generateValidToken("testuser", "acc123");
        MockServerWebExchange exchange = buildExchangeWithAuthHeader(
                "/actions/Order.action", token);

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — claims propagated as downstream headers
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username"))
                .isEqualTo("testuser");
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-AccountId"))
                .isEqualTo("acc123");
    }

    /**
     * Verifies that claims are still propagated when a valid token is present on
     * a public path ({@code /actions/Catalog.action}). The filter processes tokens
     * regardless of path — path-based access control is in SecurityConfig.
     */
    @Test
    void validJwtToken_onPublicPath_shouldPropagateClaimsAsHeaders() {
        // given
        String token = generateValidToken("testuser", "acc123");
        MockServerWebExchange exchange = buildExchangeWithAuthHeader(
                "/actions/Catalog.action", token);

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — claims still propagated even on public paths
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username"))
                .isEqualTo("testuser");
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-AccountId"))
                .isEqualTo("acc123");
    }

    // =========================================================================
    // 3b. Expired JWT Token Tests
    // =========================================================================

    /**
     * Verifies that an expired JWT token on a protected path results in the filter
     * passing through without populating SecurityContext or adding auth headers.
     *
     * <p>Spring Security's authorization rules (configured in SecurityConfig) will then
     * return 401 UNAUTHORIZED for protected paths like {@code /actions/Order.action}
     * because no authenticated SecurityContext is available. The filter itself never
     * returns 401 directly — it only populates SecurityContext on valid tokens.</p>
     *
     * <p>This mirrors the monolith's behavior where {@code OrderActionBean.newOrderForm()}
     * (line 125) checks {@code accountBean.isAuthenticated()} and redirects to the
     * sign-on page when the user is not authenticated.</p>
     */
    @Test
    void expiredJwtToken_onProtectedPath_shouldReturn401() {
        // given — expired JWT on protected path
        String token = generateExpiredToken("testuser");
        MockServerWebExchange exchange = buildExchangeWithAuthHeader(
                "/actions/Order.action", token);

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — filter passes through WITHOUT auth headers (SecurityContext not populated).
        // Spring Security's AuthorizationWebFilter will deny access and return 401.
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username")).isNull();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-AccountId")).isNull();
    }

    /**
     * Verifies that an expired JWT token on a public path does not block the request.
     * The filter passes through, and Spring Security's {@code permitAll()} rule
     * for public paths allows access even without a valid SecurityContext.
     */
    @Test
    void expiredJwtToken_onPublicPath_shouldPassThrough() {
        // given — expired JWT on public path
        String token = generateExpiredToken("testuser");
        MockServerWebExchange exchange = buildExchangeWithAuthHeader(
                "/actions/Catalog.action", token);

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — filter passes through; no error, no 401 on public paths
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        // No auth headers added — expired token treated as no token
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username")).isNull();
    }

    // =========================================================================
    // 3c. Missing JWT Token Tests
    // =========================================================================

    /**
     * Verifies that a request without any JWT token (no Authorization header, no cookie)
     * on a protected path passes through the filter without SecurityContext.
     *
     * <p>Spring Security's authorization rules will return 401 UNAUTHORIZED for
     * {@code /actions/Order.action} because no authenticated SecurityContext exists.</p>
     */
    @Test
    void missingToken_onProtectedPath_shouldReturn401() {
        // given — no token, protected path
        MockServerWebExchange exchange = buildExchange("/actions/Order.action");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — filter passes through without auth headers.
        // Spring Security's AuthorizationWebFilter enforces 401 for this path.
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username")).isNull();
    }

    /**
     * Verifies that a request without any JWT token on a protected REST API path
     * ({@code /api/orders/123}) passes through the filter without SecurityContext.
     *
     * <p>Spring Security's authorization rules will return 401 for {@code /api/orders/**}.</p>
     */
    @Test
    void missingToken_onProtectedApiPath_shouldReturn401() {
        // given — no token, protected API path
        MockServerWebExchange exchange = buildExchange("/api/orders/123");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through without SecurityContext
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username")).isNull();
    }

    /**
     * Verifies that a request without any JWT token on a public path passes through
     * the filter chain successfully. No 401, no error — public paths are accessible
     * to anonymous users.
     */
    @Test
    void missingToken_onPublicPath_shouldPassThrough() {
        // given — no token, public path
        MockServerWebExchange exchange = buildExchange("/actions/Catalog.action");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through successfully
        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    // =========================================================================
    // 3d. Malformed / Invalid JWT Token Tests
    // =========================================================================

    /**
     * Verifies that a malformed (non-JWT) token on a protected path results in the
     * filter passing through without SecurityContext. Spring Security returns 401.
     */
    @Test
    void malformedToken_onProtectedPath_shouldReturn401() {
        // given — malformed token (not a valid JWT structure)
        MockServerWebExchange exchange = buildExchangeWithAuthHeader(
                "/actions/Order.action", "not-a-valid-jwt-token");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — filter catches JwtException, passes through without auth headers
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username")).isNull();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-AccountId")).isNull();
    }

    /**
     * Verifies that a JWT token signed with a different secret key (invalid signature)
     * on a protected path results in the filter passing through without SecurityContext.
     *
     * <p>JJWT's {@code parseSignedClaims()} throws {@code SignatureException} which is
     * caught by the filter's generic {@code JwtException} handler.</p>
     */
    @Test
    void invalidSignatureToken_onProtectedPath_shouldReturn401() {
        // given — token signed with a different secret key
        String wrongKeyToken = generateTokenWithWrongKey("testuser", "acc123");
        MockServerWebExchange exchange = buildExchangeWithAuthHeader(
                "/api/orders/", wrongKeyToken);

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — filter catches SignatureException (subclass of JwtException),
        // passes through without auth headers
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username")).isNull();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-AccountId")).isNull();
    }

    // =========================================================================
    // 3e. Token Extraction Tests
    // =========================================================================

    /**
     * Verifies that the filter correctly extracts the JWT token from the
     * {@code Authorization: Bearer <token>} header (RFC 6750 Bearer Token scheme).
     */
    @Test
    void authorizationBearerHeader_shouldExtractToken() {
        // given — token in Authorization header
        String token = generateValidToken("headeruser", "h123");
        MockServerWebExchange exchange = buildExchangeWithAuthHeader(
                "/actions/Order.action", token);

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — claims extracted from header token and propagated
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username"))
                .isEqualTo("headeruser");
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-AccountId"))
                .isEqualTo("h123");
    }

    /**
     * Verifies that the filter correctly extracts the JWT token from the
     * {@code jwt-token} HTTP-only cookie when no Authorization header is present.
     *
     * <p>Per AAP Section 0.7.2, the JWT is stored as an HTTP-only cookie to support
     * the monolith's JSP-based UI without requiring JavaScript to attach the token
     * to every request.</p>
     */
    @Test
    void httpOnlyCookie_shouldExtractToken() {
        // given — token in jwt-token cookie (no Authorization header)
        String token = generateValidToken("cookieuser", "c456");
        MockServerWebExchange exchange = buildExchangeWithCookie(
                "/actions/Order.action", token);

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — claims extracted from cookie token and propagated
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username"))
                .isEqualTo("cookieuser");
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-AccountId"))
                .isEqualTo("c456");
    }

    /**
     * Verifies that when both an Authorization header and a jwt-token cookie are present,
     * the Authorization header takes precedence over the cookie.
     *
     * <p>This follows RFC 6750's standard practice where the Authorization header is the
     * primary token transport mechanism, and the cookie is a fallback for browser-based
     * access via monolith JSP pages.</p>
     */
    @Test
    void headerTakesPrecedenceOverCookie() {
        // given — BOTH header and cookie with different tokens/users
        String headerToken = generateValidToken("headeruser", "h123");
        String cookieToken = generateValidToken("cookieuser", "c456");

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/actions/Order.action")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + headerToken)
                .cookie(new HttpCookie("jwt-token", cookieToken))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — header token (headeruser) is used, not cookie (cookieuser)
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        ServerWebExchange captured = captor.getValue();
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-Username"))
                .isEqualTo("headeruser");
        assertThat(captured.getRequest().getHeaders().getFirst("X-Auth-AccountId"))
                .isEqualTo("h123");
    }

    // =========================================================================
    // 3f. Filter Order / Type Test
    // =========================================================================

    /**
     * Verifies that the AuthenticationFilter is a {@link WebFilter} instance.
     *
     * <p>The filter is registered in the Spring Security WebFilter chain via
     * {@code SecurityConfig.addFilterBefore(authenticationFilter(),
     * SecurityWebFiltersOrder.AUTHENTICATION)}, which ensures it runs before Spring
     * Security's authentication filter. The ordering is managed by SecurityConfig's
     * {@code addFilterBefore()} — not by a {@code getOrder()} method on the filter
     * itself.</p>
     *
     * <p>This placement ensures the filter runs BEFORE the {@code RoutingFlagFilter}
     * (a GlobalFilter at order 0), since the Security WebFilter chain executes
     * before the Gateway GlobalFilter chain.</p>
     */
    @Test
    void filterOrder_shouldReturnNegative100() {
        // The AuthenticationFilter is a WebFilter positioned before
        // SecurityWebFiltersOrder.AUTHENTICATION in the Security chain via
        // SecurityConfig.addFilterBefore(). This ensures it runs before all
        // GlobalFilters (including RoutingFlagFilter at order 0), effectively
        // at a position equivalent to < -100 in the overall filter execution sequence.
        assertThat(authenticationFilter).isNotNull();
        assertThat(authenticationFilter).isInstanceOf(WebFilter.class);

        // Verify the filter integrates correctly by executing a basic filter call
        MockServerWebExchange exchange = buildExchange("/actions/Catalog.action");
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    // =========================================================================
    // 3g. Protected vs Public Path Classification Tests
    // =========================================================================

    /**
     * Verifies behavior on the protected path {@code /actions/Order.action} without token.
     * The filter passes through without SecurityContext; Spring Security enforces 401.
     *
     * <p>Mirrors {@code OrderActionBean.newOrderForm()} (line 125):
     * {@code if (accountBean == null || !accountBean.isAuthenticated()) { ... }}</p>
     */
    @Test
    void orderActionPath_shouldBeProtected() {
        // given — no token, order action path
        MockServerWebExchange exchange = buildExchange("/actions/Order.action");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through; Spring Security returns 401 for this protected path
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().getFirst("X-Auth-Username"))
                .isNull();
    }

    /**
     * Verifies behavior on {@code /actions/Order.action?newOrderForm=} without token.
     * Query parameters do not affect the filter's token extraction logic.
     */
    @Test
    void orderActionPathWithParams_shouldBeProtected() {
        // given — no token, order action path with query params
        MockServerWebExchange exchange = buildExchange(
                "/actions/Order.action?newOrderForm=");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through; Spring Security returns 401
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().getFirst("X-Auth-Username"))
                .isNull();
    }

    /**
     * Verifies behavior on the protected REST API path {@code /api/orders/123} without token.
     */
    @Test
    void apiOrdersPath_shouldBeProtected() {
        // given — no token, API orders path
        MockServerWebExchange exchange = buildExchange("/api/orders/123");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through; Spring Security returns 401
        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(filterChain).filter(captor.capture());
        assertThat(captor.getValue().getRequest().getHeaders().getFirst("X-Auth-Username"))
                .isNull();
    }

    /**
     * Verifies that the public path {@code /actions/Catalog.action} passes through
     * without any authentication requirement. Catalog browsing is fully public.
     *
     * <p>CatalogActionBean has zero authentication checks — all methods (viewCategory,
     * viewProduct, viewItem, searchProducts) are public.</p>
     */
    @Test
    void catalogActionPath_shouldBePublic() {
        // given — no token, public catalog path
        MockServerWebExchange exchange = buildExchange("/actions/Catalog.action");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through successfully; no auth required
        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    /**
     * Verifies that {@code /actions/Account.action} is accessible without token.
     * Account sign-on and registration forms are publicly accessible.
     */
    @Test
    void accountActionPath_shouldBePublic() {
        // given — no token, public account path
        MockServerWebExchange exchange = buildExchange("/actions/Account.action");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through; account actions are public during coexistence
        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    /**
     * Verifies that {@code /actions/Cart.action} is accessible without token.
     * Unauthenticated users can browse and build a cart before signing in
     * (AAP Section 0.7.2).
     */
    @Test
    void cartActionPath_shouldBePublic() {
        // given — no token, public cart path
        MockServerWebExchange exchange = buildExchange("/actions/Cart.action");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through; cart is accessible to anonymous users
        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    /**
     * Verifies that static CSS asset paths pass through without authentication.
     */
    @Test
    void staticCssPath_shouldBePublic() {
        // given — no token, static CSS path
        MockServerWebExchange exchange = buildExchange("/css/jpetstore.css");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through; static assets never require auth
        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    /**
     * Verifies that static image asset paths pass through without authentication.
     */
    @Test
    void staticImagesPath_shouldBePublic() {
        // given — no token, static image path
        MockServerWebExchange exchange = buildExchange("/images/logo.gif");

        // when
        StepVerifier.create(authenticationFilter.filter(exchange, filterChain))
                .verifyComplete();

        // then — passes through; static assets never require auth
        verify(filterChain).filter(any(ServerWebExchange.class));
    }

    // =========================================================================
    // Helper Methods — Token Generation
    // =========================================================================

    /**
     * Generates a valid JWT token with the specified username (subject) and accountId
     * (custom claim), signed with the test HMAC-SHA256 secret key.
     *
     * <p>Uses JJWT 0.12.6 API: {@code Jwts.builder().subject().claim().issuer()
     * .issuedAt().expiration().signWith().compact()}</p>
     *
     * @param username  the JWT subject claim (user identifier)
     * @param accountId the custom accountId claim
     * @return a signed JWT token string valid for 1 hour
     */
    private String generateValidToken(String username, String accountId) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(username)
                .claim("accountId", accountId)
                .issuer(JWT_ISSUER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000)) // 1 hour from now
                .signWith(key)
                .compact();
    }

    /**
     * Generates an expired JWT token (expired 1 hour ago, issued 2 hours ago).
     *
     * @param username the JWT subject claim
     * @return a signed but expired JWT token string
     */
    private String generateExpiredToken(String username) {
        SecretKey key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(username)
                .issuer(JWT_ISSUER)
                .issuedAt(new Date(System.currentTimeMillis() - 7200000))  // 2 hours ago
                .expiration(new Date(System.currentTimeMillis() - 3600000)) // 1 hour ago
                .signWith(key)
                .compact();
    }

    /**
     * Generates a JWT token signed with a DIFFERENT secret key than the filter uses.
     * The filter will reject this token with a {@code SignatureException}.
     *
     * @param username  the JWT subject claim
     * @param accountId the custom accountId claim
     * @return a JWT token signed with the wrong key
     */
    private String generateTokenWithWrongKey(String username, String accountId) {
        String differentSecret =
                "different-secret-key-that-does-not-match-the-filter-configuration-key";
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                differentSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(username)
                .claim("accountId", accountId)
                .issuer(JWT_ISSUER)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(wrongKey)
                .compact();
    }

    // =========================================================================
    // Helper Methods — Exchange Construction
    // =========================================================================

    /**
     * Builds a {@link MockServerWebExchange} for the given path with no authentication
     * credentials (no Authorization header, no jwt-token cookie).
     *
     * @param path the request URI path
     * @return a mock exchange targeting the specified path
     */
    private MockServerWebExchange buildExchange(String path) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
        return MockServerWebExchange.from(request);
    }

    /**
     * Builds a {@link MockServerWebExchange} with an {@code Authorization: Bearer <token>}
     * header for the specified path.
     *
     * @param path  the request URI path
     * @param token the JWT token to include in the Bearer header
     * @return a mock exchange with the Authorization header set
     */
    private MockServerWebExchange buildExchangeWithAuthHeader(String path, String token) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
        return MockServerWebExchange.from(request);
    }

    /**
     * Builds a {@link MockServerWebExchange} with a {@code jwt-token} cookie containing
     * the JWT token for the specified path. No Authorization header is set.
     *
     * @param path  the request URI path
     * @param token the JWT token to include in the cookie
     * @return a mock exchange with the jwt-token cookie set
     */
    private MockServerWebExchange buildExchangeWithCookie(String path, String token) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path)
                .cookie(new HttpCookie("jwt-token", token))
                .build();
        return MockServerWebExchange.from(request);
    }
}
