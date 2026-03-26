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
package com.jpetstore.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * WebFlux security chain configuration tests for the JPetStore API Gateway.
 *
 * <p>Verifies that the {@link SecurityConfig}'s {@code SecurityWebFilterChain} correctly
 * enforces authentication for protected paths and permits public access to catalog
 * browsing, cart management, sign-on, registration, static assets, and actuator
 * endpoints.
 *
 * <h3>Authentication Enforcement Rationale</h3>
 * <p>The gateway's security rules mirror the monolith's session-scoped authentication
 * enforcement. Specifically, {@code OrderActionBean.newOrderForm()} (line 125) checks
 * {@code if (accountBean == null || !accountBean.isAuthenticated())} before allowing
 * checkout. The gateway enforces this at the edge via JWT authentication on
 * {@code /actions/Order.action**} and {@code /api/orders/**} paths.
 *
 * <h3>Test Approach — Reactive WebFlux</h3>
 * <p><strong>CRITICAL</strong>: This is a <em>reactive</em> WebFlux security test.
 * It uses {@link WebTestClient} and
 * {@link org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers}
 * — NOT servlet-based {@code MockMvc} or {@code SecurityMockMvcConfigurers}.</p>
 *
 * <h3>Test Isolation</h3>
 * <p>External dependencies are mocked to isolate the security chain:
 * <ul>
 *   <li>{@code ReactiveRedisConnectionFactory} — prevents actual Redis connection</li>
 *   <li>{@code RouteLocator} — prevents gateway route auto-configuration from loading</li>
 * </ul>
 *
 * @see SecurityConfig
 * @see org.springframework.security.web.server.SecurityWebFilterChain
 */
@WebFluxTest(excludeAutoConfiguration = {
        ReactiveSecurityAutoConfiguration.class,
        ReactiveUserDetailsServiceAutoConfiguration.class
})
@Import(SecurityConfig.class)
class SecurityConfigTest {

    /**
     * Reactive web test client auto-configured by {@code @WebFluxTest}.
     * Used to issue HTTP requests against the security filter chain and
     * assert response status codes.
     */
    @Autowired
    private WebTestClient webTestClient;

    /**
     * Mock Redis connection factory to prevent the test from attempting to
     * connect to a real Redis server. The gateway uses Redis for routing
     * flags (Strangler Fig pattern), but security chain testing does not
     * require an active Redis connection.
     */
    @MockitoBean
    private ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    /**
     * Mock reactive Redis template to satisfy the {@link SecurityConfig}'s
     * {@code @Autowired ReactiveRedisTemplate<String, String>} dependency.
     * The gateway uses Redis for routing flags and token revocation checks,
     * but security chain testing does not require actual Redis operations.
     */
    @MockitoBean
    private org.springframework.data.redis.core.ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    /**
     * Mock route locator to prevent Spring Cloud Gateway's route
     * auto-configuration from loading in the {@code @WebFluxTest} slice.
     * This isolates the security chain for focused testing.
     */
    @MockitoBean
    private RouteLocator routeLocator;

    // =========================================================================
    // Protected Paths — Require Authentication
    // =========================================================================

    /**
     * Verifies that {@code GET /actions/Order.action} requires authentication.
     *
     * <p>Source context: {@code OrderActionBean.newOrderForm()} (line 125) checks
     * {@code accountBean.isAuthenticated()} — the gateway enforces this at the
     * edge per AAP Section 0.7.6.
     *
     * <p>Also covers {@code OrderActionBean.listOrders()} (line 109-110) which
     * retrieves the account from session to fetch orders by username.
     */
    @Test
    void orderActionShouldRequireAuthentication() {
        webTestClient.get()
                .uri("/actions/Order.action")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Verifies that an authenticated user can access {@code /actions/Order.action}.
     *
     * <p>Uses {@link org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers#mockUser(String)}
     * to simulate an authenticated request. The response status may be 404 (no route
     * target in test) but must NOT be 401 Unauthorized — the key assertion is that
     * the security check passes for authenticated users.
     */
    @Test
    void orderActionShouldSucceedWithAuthentication() {
        webTestClient
                .mutateWith(mockUser("testuser"))
                .get()
                .uri("/actions/Order.action")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("Authenticated user should not receive 401 Unauthorized")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    /**
     * Verifies that {@code GET /api/orders/{id}} requires authentication.
     *
     * <p>REST API order operations mirror the same protection applied to the
     * monolith's order ActionBean. All order-related endpoints are protected
     * to ensure only authenticated users can view or create orders.
     */
    @Test
    void apiOrdersShouldRequireAuthentication() {
        webTestClient.get()
                .uri("/api/orders/123")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /**
     * Verifies that {@code GET /api/accounts/{username}} requires authentication.
     *
     * <p>Account data access by username is protected so that users may only
     * view their own profile data. The sign-on and registration endpoints
     * are separate public paths.
     */
    @Test
    void apiAccountGetShouldRequireAuthentication() {
        webTestClient.get()
                .uri("/api/accounts/testuser")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // =========================================================================
    // Public Paths — Permit All (No Authentication Required)
    // =========================================================================

    /**
     * Verifies that {@code GET /actions/Catalog.action} is publicly accessible.
     *
     * <p>CatalogActionBean has zero authentication checks in its source code — all
     * methods ({@code viewCategory}, {@code viewProduct}, {@code viewItem},
     * {@code searchProducts}) are public. Confirms {@code /actions/Catalog.action**}
     * is configured as {@code permitAll()}.
     */
    @Test
    void catalogActionShouldBePublic() {
        webTestClient.get()
                .uri("/actions/Catalog.action")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("Catalog action should be publicly accessible (not 401)")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    /**
     * Verifies that {@code GET /actions/Cart.action} is publicly accessible.
     *
     * <p>AAP Section 0.7.2 states: "Unauthenticated users can browse and build a
     * cart before signing in." The anonymous cart is identified by a session cookie
     * and merged on login, preserving the monolith's existing behavior.
     */
    @Test
    void cartActionShouldBePublic() {
        webTestClient.get()
                .uri("/actions/Cart.action")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("Cart action should be publicly accessible (not 401)")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    /**
     * Verifies that {@code GET /actions/Account.action} is publicly accessible.
     *
     * <p>{@code /actions/Account.action} must be publicly accessible for the
     * sign-on form ({@code AccountActionBean.signonForm()}) and new account form
     * ({@code AccountActionBean.newAccountForm()}). During coexistence, the
     * monolith's session-scoped AccountActionBean handles its own authentication
     * checks for edit operations internally.
     */
    @Test
    void accountActionShouldBePublic() {
        webTestClient.get()
                .uri("/actions/Account.action")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("Account action should be publicly accessible (not 401)")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    /**
     * Verifies that {@code GET /css/jpetstore.css} is publicly accessible.
     *
     * <p>Static CSS assets are always served from the monolith and require
     * no authentication. Configured as {@code permitAll()} in SecurityConfig.
     */
    @Test
    void cssAssetsShouldBePublic() {
        webTestClient.get()
                .uri("/css/jpetstore.css")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("CSS assets should be publicly accessible (not 401)")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    /**
     * Verifies that {@code GET /images/logo.gif} is publicly accessible.
     *
     * <p>Static image assets are always served from the monolith and require
     * no authentication. Configured as {@code permitAll()} in SecurityConfig.
     */
    @Test
    void imageAssetsShouldBePublic() {
        webTestClient.get()
                .uri("/images/logo.gif")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("Image assets should be publicly accessible (not 401)")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    /**
     * Verifies that {@code GET /actuator/health} is publicly accessible.
     *
     * <p>Actuator health endpoints are used by container orchestration (Docker,
     * Kubernetes) and monitoring infrastructure for readiness and liveness probes.
     * These must be accessible without authentication.
     */
    @Test
    void actuatorHealthShouldBePublic() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("Actuator health should be publicly accessible (not 401)")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    /**
     * Verifies that {@code POST /api/accounts/signon} is publicly accessible.
     *
     * <p>The authentication (sign-on) endpoint must be publicly accessible —
     * users need to reach this endpoint to obtain a JWT token. Configured
     * as {@code permitAll()} in SecurityConfig.
     */
    @Test
    void signonApiShouldBePublic() {
        webTestClient.post()
                .uri("/api/accounts/signon")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("Signon API should be publicly accessible (not 401)")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    /**
     * Verifies that {@code POST /api/accounts} (registration) is publicly accessible.
     *
     * <p>The registration endpoint must be publicly accessible — new users need
     * to create accounts without prior authentication. Configured as
     * {@code permitAll()} for POST method in SecurityConfig.
     */
    @Test
    void registrationApiShouldBePublic() {
        webTestClient.post()
                .uri("/api/accounts")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("Registration API should be publicly accessible (not 401)")
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    // =========================================================================
    // Security Chain Configuration Verification
    // =========================================================================

    /**
     * Verifies that CSRF protection is disabled in the security chain.
     *
     * <p>Sends a POST request without a CSRF token to a public endpoint.
     * If CSRF were enabled, this request would be rejected with 403 Forbidden
     * due to missing CSRF token. Since the gateway is a stateless JWT-based
     * router, CSRF protection is unnecessary (AAP Section 0.7.6).
     */
    @Test
    void csrfShouldBeDisabled() {
        webTestClient.post()
                .uri("/api/accounts/signon")
                .exchange()
                .expectStatus().value(status ->
                        assertThat(status)
                                .as("POST without CSRF token should not be rejected with 403")
                                .isNotEqualTo(HttpStatus.FORBIDDEN.value()));
    }

    /**
     * Verifies that HTTP Basic authentication is disabled in the security chain.
     *
     * <p>Sends an unauthenticated request to a protected path and verifies that
     * the response does NOT include a {@code WWW-Authenticate} header. When HTTP
     * Basic is enabled, Spring Security responds with 401 and a
     * {@code WWW-Authenticate: Basic realm="..."} challenge. The gateway uses
     * JWT tokens exclusively for authentication, not HTTP Basic credentials.
     */
    @Test
    void httpBasicShouldBeDisabled() {
        webTestClient.get()
                .uri("/actions/Order.action")
                .exchange()
                .expectHeader().doesNotExist("WWW-Authenticate");
    }

    /**
     * Verifies that form login is disabled in the security chain.
     *
     * <p>When form login is enabled, Spring Security generates a login page at
     * {@code /login} and returns 200 OK with HTML form content. When disabled,
     * {@code /login} falls through to the default {@code anyExchange().permitAll()}
     * rule, and since no handler exists for this path, it returns 404 Not Found.
     * The gateway uses JWT tokens issued by Account Service, not form-based login.
     */
    @Test
    void formLoginShouldBeDisabled() {
        webTestClient.get()
                .uri("/login")
                .exchange()
                .expectStatus().isNotFound();
    }
}
