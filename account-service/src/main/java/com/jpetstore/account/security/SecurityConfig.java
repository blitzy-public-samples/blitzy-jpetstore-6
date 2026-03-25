/*
 *    Copyright 2010-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.jpetstore.account.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the Account Service microservice.
 *
 * <p>This configuration replaces the monolith's session-scoped authentication mechanism
 * ({@code AccountActionBean.authenticated} flag stored in the HTTP session) with a stateless,
 * JWT-based security model. In the original monolith, authentication state was maintained via
 * a {@code @SessionScope} ActionBean that set {@code authenticated = true} after successful
 * sign-on. In the new microservices architecture, the Account Service issues JWT tokens upon
 * authentication, and this security configuration enforces stateless request handling.</p>
 *
 * <h3>Authorization Rules</h3>
 * <ul>
 *   <li>{@code POST /api/accounts/signon} — Permit all (authentication endpoint; no existing token required)</li>
 *   <li>{@code POST /api/accounts} — Permit all (registration endpoint; new users have no token)</li>
 *   <li>{@code /actuator/health} — Permit all (container orchestration readiness/liveness probes)</li>
 *   <li>All other requests — Require authentication</li>
 * </ul>
 *
 * <h3>Defense-in-Depth</h3>
 * <p>The API Gateway's {@code AuthenticationFilter} validates JWTs at the edge and propagates
 * {@code X-Auth-Username} and {@code X-Auth-AccountId} headers downstream. This SecurityConfig
 * provides a second layer of protection at the service level, ensuring that even direct
 * service-to-service calls respect the authorization rules.</p>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>CSRF is disabled because this is a stateless REST API — no browser session cookies
 *       are used for authentication, so CSRF tokens are unnecessary.</li>
 *   <li>Session creation policy is set to {@code STATELESS} — the server never creates or
 *       uses HTTP sessions. Authentication state is carried entirely in JWT tokens.</li>
 *   <li>Uses the Spring Security 6.x {@code SecurityFilterChain} bean pattern, not the
 *       deprecated {@code WebSecurityConfigurerAdapter} that was removed in Spring Security 6.</li>
 *   <li>Uses the lambda DSL for configuration as recommended by Spring Security 6.x.</li>
 * </ul>
 *
 * @see com.jpetstore.account.controller.AccountController
 * @see com.jpetstore.account.security.JwtTokenProvider
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Configures the HTTP security filter chain for the Account Service.
     *
     * <p>Establishes the following security posture:</p>
     * <ol>
     *   <li>CSRF protection is disabled — appropriate for a stateless REST API that authenticates
     *       via JWT bearer tokens rather than session cookies.</li>
     *   <li>Session management is set to {@link SessionCreationPolicy#STATELESS} — the server
     *       will never create an HTTP session. This aligns with the AAP requirement (Section 0.7.2)
     *       to externalize authentication state from server-side sessions to JWT tokens.</li>
     *   <li>Authorization rules are configured per the API contract defined in AAP Section 0.4.1:
     *       <ul>
     *         <li>{@code POST /api/accounts/signon} is publicly accessible for authentication</li>
     *         <li>{@code POST /api/accounts} is publicly accessible for new user registration</li>
     *         <li>{@code /actuator/health} is publicly accessible for health checks</li>
     *         <li>All other endpoints ({@code GET /api/accounts/{username}},
     *             {@code PUT /api/accounts/{username}}) require authentication</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * @param http the {@link HttpSecurity} builder provided by Spring Security
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if an error occurs during security configuration
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — stateless REST API using JWT tokens, not session cookies
            .csrf(csrf -> csrf.disable())

            // Enforce stateless session management — no HTTP session creation.
            // Replaces the monolith's @SessionScope AccountActionBean.authenticated flag
            // with JWT-based authentication carried in the Authorization header.
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Configure endpoint-level authorization rules
            .authorizeHttpRequests(auth -> auth
                // Authentication endpoint — must be accessible without an existing token.
                // In the monolith, this was the AccountActionBean.signon() method (lines 159-177)
                // that validated username/password against AccountService.getAccount(username, password).
                .requestMatchers(HttpMethod.POST, "/api/accounts/signon").permitAll()

                // Registration endpoint — new users registering do not have a token.
                // In the monolith, this was AccountActionBean.newAccount() (lines 115-121)
                // that called AccountService.insertAccount(account).
                .requestMatchers(HttpMethod.POST, "/api/accounts").permitAll()

                // Health check endpoint — container orchestration (Docker/Kubernetes)
                // readiness and liveness probes must access this without authentication.
                .requestMatchers("/actuator/health").permitAll()

                // All other endpoints require authentication:
                // - GET /api/accounts/{username} — account retrieval
                // - PUT /api/accounts/{username} — account update
                // These correspond to the monolith's isAuthenticated() check
                // (AccountActionBean line 195-197) that guarded account operations.
                .anyRequest().authenticated()
            );

        return http.build();
    }

    /**
     * Provides a {@link PasswordEncoder} bean for password hashing and verification.
     *
     * <p>Uses BCrypt, a strong adaptive hashing function designed for password storage.
     * BCrypt automatically handles salt generation and incorporates a configurable work
     * factor (default: 10 rounds = 2^10 iterations), making it resistant to brute-force
     * and rainbow table attacks.</p>
     *
     * <p><strong>Migration Note:</strong> The original monolith stores plaintext passwords
     * in the {@code signon} table. During the dual-write coexistence window, the Account
     * Service must handle both BCrypt-hashed passwords (for new registrations) and
     * plaintext passwords (for accounts migrated from the monolith's HSQLDB). The
     * {@code AccountService} business logic layer is responsible for detecting and
     * handling this transition — this bean simply provides the encoding mechanism.</p>
     *
     * @return a {@link BCryptPasswordEncoder} instance for password hashing
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
