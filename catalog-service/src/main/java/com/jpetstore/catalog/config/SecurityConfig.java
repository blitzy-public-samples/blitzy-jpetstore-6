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
package com.jpetstore.catalog.config;

import com.jpetstore.catalog.security.JwtAuthenticationFilter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security configuration for the Catalog Service.
 *
 * <p>The Catalog Service exposes read-only public endpoints (categories, products,
 * items browsing) and internal inventory management endpoints consumed by the
 * Order Service. All requests are permitted without authentication — access
 * control is enforced at the API Gateway level.</p>
 *
 * <p>This configuration exists solely for <strong>defense-in-depth security headers</strong>.
 * Spring Security's default header writer adds standard security headers to all
 * HTTP responses:</p>
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — Prevents MIME type sniffing</li>
 *   <li>{@code X-Frame-Options: DENY} — Prevents clickjacking via iframes</li>
 *   <li>{@code X-XSS-Protection: 0} — Disables legacy XSS auditor (modern best practice)</li>
 *   <li>{@code Referrer-Policy: no-referrer} — Controls referrer information leakage</li>
 *   <li>{@code Cache-Control: no-cache, no-store, max-age=0, must-revalidate} — Prevents caching of sensitive data</li>
 *   <li>{@code Pragma: no-cache} — HTTP/1.0 backward-compatible no-cache directive</li>
 *   <li>{@code Expires: 0} — Marks response as immediately expired</li>
 * </ul>
 *
 * <p>Even though the API Gateway also adds these headers, defense-in-depth requires
 * that each service independently protects its responses in case of direct access
 * (e.g., during development, internal service-to-service calls, or misconfigured
 * network policies).</p>
 *
 * @see org.springframework.security.config.annotation.web.configurers.HeadersConfigurer
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** HMAC-SHA secret key for JWT signature verification (shared with Account Service and Gateway). */
    @Value("${jwt.secret:jpetstore-jwt-secret-key-for-development-only-change-in-production}")
    private String jwtSecret;

    /** Expected issuer claim for JWT tokens. */
    @Value("${jwt.issuer:jpetstore}")
    private String jwtIssuer;

    /**
     * Creates the JWT authentication filter for defense-in-depth validation.
     *
     * <p>This filter validates JWT tokens directly on the Catalog Service so that
     * even if the service port (8082) is directly accessible (bypassing the API
     * Gateway), write operations on protected endpoints (inventory decrement/restore)
     * are still authenticated.</p>
     *
     * @return the configured {@link JwtAuthenticationFilter} instance
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtSecret, jwtIssuer);
    }

    /**
     * Configures the security filter chain with defense-in-depth authentication.
     *
     * <p>Read-only catalog browsing endpoints (GET on categories, products, items)
     * remain publicly accessible. Write operations on inventory (POST decrement/restore)
     * require JWT authentication to prevent unauthorized inventory manipulation
     * even when the service port is directly reachable.</p>
     *
     * <p>Configuration details:</p>
     * <ul>
     *   <li><strong>Public GET endpoints</strong>: All GET requests on catalog resources
     *       are publicly accessible for browsing without authentication.</li>
     *   <li><strong>Protected write endpoints</strong>: POST requests to inventory
     *       decrement and restore endpoints require authentication.</li>
     *   <li><strong>Actuator health/info</strong>: Public for orchestration probes.</li>
     *   <li><strong>All other requests</strong>: Require authentication by default.</li>
     * </ul>
     *
     * @param http the {@link HttpSecurity} builder to configure
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if security configuration fails
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — stateless REST API, no browser form submissions
            .csrf(csrf -> csrf.disable())

            // Register JWT filter before Spring Security's UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthenticationFilter(),
                    UsernamePasswordAuthenticationFilter.class)

            // Path-based authorization rules for defense-in-depth
            .authorizeHttpRequests(auth -> auth
                // Actuator health and info for orchestration probes
                .requestMatchers("/actuator/health", "/actuator/health/**",
                        "/actuator/info").permitAll()
                // All GET requests on catalog resources are public (browsing)
                .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/items/**").permitAll()
                // Inventory write operations require authentication
                .requestMatchers(HttpMethod.POST, "/api/items/*/inventory/**").authenticated()
                // All other requests require authentication
                .anyRequest().authenticated()
            )

            // Return 401 instead of redirect to login page for unauthorized requests
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

            // Stateless session management — no HTTP sessions created
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Default security headers
            .headers(Customizer.withDefaults());

        return http.build();
    }
}
