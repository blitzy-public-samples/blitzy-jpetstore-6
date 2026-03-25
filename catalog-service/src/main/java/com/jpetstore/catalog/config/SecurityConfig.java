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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

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

    /**
     * Configures the security filter chain to permit all requests while
     * maintaining Spring Security's default security header output.
     *
     * <p>Configuration details:</p>
     * <ul>
     *   <li><strong>CSRF disabled</strong> — Catalog Service is a stateless REST API
     *       with no browser form submissions; CSRF protection is not applicable</li>
     *   <li><strong>All requests permitted</strong> — Authentication is handled at
     *       the API Gateway; the catalog service trusts internal network traffic</li>
     *   <li><strong>Stateless sessions</strong> — No HTTP sessions are created;
     *       the service is fully stateless per the microservices architecture</li>
     *   <li><strong>Default security headers</strong> — Spring Security's default
     *       header configuration is retained (not customized), providing all standard
     *       security headers listed in the class-level Javadoc</li>
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
            // Permit all requests — auth is enforced at the API Gateway layer
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // Stateless session management — no HTTP sessions created
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Default security headers are automatically applied by Spring Security.
            // Using Customizer.withDefaults() explicitly retains all default headers:
            // X-Content-Type-Options, X-Frame-Options, X-XSS-Protection,
            // Referrer-Policy, Cache-Control, Pragma, Expires
            .headers(Customizer.withDefaults());

        return http.build();
    }
}
