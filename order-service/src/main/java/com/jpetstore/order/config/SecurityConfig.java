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
package com.jpetstore.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security configuration for the Order Service.
 *
 * <p>Provides defense-in-depth security headers (X-Content-Type-Options: nosniff,
 * X-Frame-Options: DENY, Cache-Control, etc.) matching the security posture of
 * the Account Service and Catalog Service.</p>
 *
 * <h3>Security Model</h3>
 * <ul>
 *   <li><strong>Cart endpoints</strong> ({@code /api/cart/**}): Permit anonymous access.
 *       Per AAP §0.7.2, unauthenticated users can browse and build a cart before
 *       signing in. The anonymous cart is identified by a session cookie.</li>
 *   <li><strong>Order endpoints</strong> ({@code /api/orders/**}): Currently permit all
 *       requests. Authentication enforcement is handled at the API Gateway layer via
 *       {@code JwtAuthenticationFilter} (per AAP §0.7.6). When full JWT validation is
 *       deployed within the service, this configuration can be updated to require
 *       authentication on order endpoints.</li>
 *   <li><strong>Actuator endpoints</strong> ({@code /actuator/**}): Permit all for
 *       health checks and readiness probes used by container orchestration.</li>
 * </ul>
 *
 * <h3>Session Management</h3>
 * <p>Stateless session policy (STATELESS) — per the microservices design, each service
 * is stateless and authentication state is carried via JWT tokens, not server-side
 * sessions. This aligns with the session externalization strategy (AAP §0.7.2).</p>
 *
 * <h3>CSRF Protection</h3>
 * <p>CSRF is disabled because this is a REST API consumed by programmatic clients
 * (the monolith's ActionBeans via RestTemplate, the API Gateway, and inter-service
 * REST clients). Browser-based CSRF protection is handled at the monolith layer.</p>
 *
 * @author Blitzy Platform
 * @see org.springframework.security.web.SecurityFilterChain
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Configures the HTTP security filter chain for the Order Service.
     *
     * <p>This configuration mirrors the Catalog Service's security setup,
     * providing consistent security headers across all microservices while
     * permitting all HTTP requests (authentication enforcement delegated to
     * the API Gateway per AAP §0.7.6).</p>
     *
     * <p>Security headers enabled by {@code Customizer.withDefaults()}:</p>
     * <ul>
     *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-type sniffing</li>
     *   <li>{@code X-Frame-Options: DENY} — prevents clickjacking</li>
     *   <li>{@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate}</li>
     *   <li>{@code Pragma: no-cache} — HTTP/1.0 cache prevention</li>
     *   <li>{@code X-XSS-Protection: 0} — disables browser XSS filter (modern CSP preferred)</li>
     * </ul>
     *
     * @param http the {@link HttpSecurity} builder to configure
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if security configuration fails
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Disable CSRF — REST API consumed by programmatic clients, not browsers
                .csrf(csrf -> csrf.disable())
                // Permit all requests — authentication enforced at API Gateway layer
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                // Stateless session — no server-side session; JWT-based authentication
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Enable default security headers for defense-in-depth
                .headers(Customizer.withDefaults());

        return http.build();
    }
}
