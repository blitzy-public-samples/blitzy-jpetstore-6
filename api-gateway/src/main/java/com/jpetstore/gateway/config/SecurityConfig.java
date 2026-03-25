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

import com.jpetstore.gateway.filter.AuthenticationFilter;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Spring Security WebFlux configuration for the JPetStore API Gateway.
 *
 * <p>Defines path-based access control rules that mirror the monolith's authentication
 * enforcement patterns during the Strangler Fig coexistence period. The gateway
 * enforces JWT authentication for protected paths while permitting public access to
 * catalog browsing, cart management, sign-on, and registration endpoints.
 *
 * <h3>Authentication Enforcement Strategy</h3>
 * <ul>
 *   <li><strong>Order operations</strong> ({@code /actions/Order.action**}, {@code /api/orders/**})
 *       require authentication, mirroring {@code OrderActionBean.newOrderForm()} which checks
 *       {@code accountBean.isAuthenticated()} before allowing checkout.</li>
 *   <li><strong>Account access by username</strong> ({@code /api/accounts/{username}} GET/PUT)
 *       requires authentication to protect individual account data.</li>
 *   <li><strong>All other paths</strong> are publicly accessible. During coexistence, the monolith
 *       handles its own session-based authentication for {@code *.action} paths internally.</li>
 * </ul>
 *
 * <h3>Reactive Stack Requirement</h3>
 * <p>Spring Cloud Gateway runs on Netty — this is a reactive (WebFlux) security configuration.
 * Uses {@link ServerHttpSecurity} and {@link SecurityWebFilterChain}, NOT the servlet-based
 * {@code HttpSecurity} and {@code SecurityFilterChain}.
 *
 * <h3>JWT Validation Integration</h3>
 * <p>JWT token validation is performed by the {@link AuthenticationFilter} which is registered
 * in this security filter chain via
 * {@code addFilterBefore(SecurityWebFiltersOrder.AUTHENTICATION)}. This ensures JWT
 * validation runs in the SAME WebFilter chain as Spring Security's
 * {@code AuthorizationWebFilter}, so the populated {@code SecurityContext} is visible
 * to the authorization check. Using a {@code GlobalFilter} (which runs in a separate
 * Gateway filter chain) would not work because the SecurityContext would not propagate
 * across filter chains.</p>
 *
 * <h3>Case-Insensitive Path Matching</h3>
 * <p>All path matchers use {@link PathPatternParser} with {@code setCaseSensitive(false)}
 * to prevent case-based security bypass. Without this, requests to
 * {@code /ACTIONS/ORDER.ACTION} would bypass auth rules for {@code /actions/Order.action}
 * and fall through to {@code anyExchange().permitAll()}.</p>
 *
 * <h3>CORS Support</h3>
 * <p>CORS is configured to allow browser-based cross-origin API calls. OPTIONS preflight
 * requests receive 200 with CORS headers before hitting authorization rules.</p>
 *
 * @see com.jpetstore.gateway.filter.AuthenticationFilter
 * @see org.springframework.security.config.web.server.ServerHttpSecurity
 * @see org.springframework.security.web.server.SecurityWebFilterChain
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // -------------------------------------------------------------------------
    // JWT Configuration (injected from application.yml)
    // -------------------------------------------------------------------------

    /** HMAC-SHA secret key for JWT signature verification. */
    @Value("${jwt.secret:jpetstore-jwt-secret-key-for-development-only-change-in-production}")
    private String jwtSecret;

    /** Expected issuer claim for JWT tokens. */
    @Value("${jwt.issuer:jpetstore}")
    private String jwtIssuer;

    // -------------------------------------------------------------------------
    // Bean Definitions
    // -------------------------------------------------------------------------

    /**
     * Creates the JWT authentication filter as a Spring bean.
     *
     * <p>This filter is NOT a {@code @Component} — it is explicitly constructed here
     * so that it can be registered in the Spring Security filter chain at the correct
     * position via {@code addFilterBefore(SecurityWebFiltersOrder.AUTHENTICATION)}.
     * This ensures JWT validation runs BEFORE Spring Security's authorization check,
     * within the same WebFilter chain.</p>
     *
     * @return the configured {@link AuthenticationFilter} instance
     */
    @Bean
    public AuthenticationFilter authenticationFilter() {
        return new AuthenticationFilter(jwtSecret, jwtIssuer);
    }

    /**
     * Configures the reactive security filter chain with path-based authorization rules
     * for the API Gateway.
     *
     * <p>The configuration is designed for the Strangler Fig coexistence period where:
     * <ul>
     *   <li>The monolith handles its own session-based auth for most {@code *.action} paths</li>
     *   <li>The gateway enforces JWT auth for {@code /actions/Order.action**} at the edge</li>
     *   <li>The gateway enforces JWT auth for new REST API paths ({@code /api/**})</li>
     *   <li>CSRF, HTTP basic, and form login are all disabled for stateless JWT-based routing</li>
     *   <li>All path matchers are case-insensitive to prevent security bypass</li>
     *   <li>CORS is configured to allow browser-based cross-origin API calls</li>
     * </ul>
     *
     * <p><strong>Path Evaluation Order</strong>: Spring Security evaluates path matchers in the
     * order they are declared. More specific patterns (e.g., {@code /api/accounts/signon}) are
     * declared before broader patterns (e.g., {@code /api/accounts/{username}}) to ensure
     * correct matching precedence.
     *
     * @param http the reactive {@link ServerHttpSecurity} builder provided by Spring Security
     * @return the configured {@link SecurityWebFilterChain} for the API Gateway
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // Disable CSRF protection — the API Gateway is a stateless router using
                // JWT-based authentication. CSRF tokens are unnecessary for stateless APIs
                // that do not maintain server-side sessions (AAP Section 0.7.6).
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // Configure CORS to allow browser-based cross-origin API calls.
                // This handles OPTIONS preflight requests with proper CORS headers
                // before the authorization check, preventing 401 on preflight.
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // Register the JWT AuthenticationFilter in the Security WebFilter chain
                // BEFORE the AUTHENTICATION filter position. This ensures:
                //   1. AuthenticationFilter runs → validates JWT → populates SecurityContext
                //   2. AuthorizationWebFilter runs → checks SecurityContext → permits/denies
                // Previously, AuthenticationFilter was a GlobalFilter (order -100), which
                // ran in a separate Gateway filter chain AFTER the Security WebFilter chain,
                // making the SecurityContext invisible to the authorization check.
                .addFilterBefore(authenticationFilter(), SecurityWebFiltersOrder.AUTHENTICATION)

                // Define path-based authorization rules using case-insensitive matchers.
                // All matchers use ciMatcher() which internally uses PathPatternParser with
                // setCaseSensitive(false) to prevent case-based security bypass (e.g.,
                // /ACTIONS/ORDER.ACTION bypassing /actions/Order.action** rules).
                .authorizeExchange(exchanges -> exchanges

                        // ================================================================
                        // PUBLIC PATHS — No authentication required
                        // ================================================================

                        // Catalog browsing is fully public. CatalogActionBean has zero
                        // authentication checks in its source code — all methods
                        // (viewCategory, viewProduct, viewItem, searchProducts) are public.
                        .matchers(ciMatcher("/actions/Catalog.action**")).permitAll()

                        // Account action paths are publicly accessible during coexistence.
                        // The Stripes framework routes all account operations (signonForm,
                        // signon, newAccountForm, newAccount, editAccountForm, editAccount)
                        // through /actions/Account.action with different _eventName params.
                        // The monolith's session-scoped AccountActionBean handles its own
                        // authentication checks for edit operations internally.
                        .matchers(ciMatcher("/actions/Account.action**")).permitAll()

                        // Cart operations are public — unauthenticated users can browse and
                        // build a cart before signing in. This preserves the monolith's
                        // existing behavior (AAP Section 0.7.2: "Unauthenticated users can
                        // browse and build a cart before signing in").
                        .matchers(ciMatcher("/actions/Cart.action**")).permitAll()

                        // Cart REST API is public — externalized cart state mirrors the
                        // monolith's session-scoped CartActionBean which allowed unauthenticated
                        // access. Anonymous users build carts keyed by session cookie; on login,
                        // the anonymous cart is merged into the user-keyed cart.
                        .matchers(ciMatcher("/api/cart/**")).permitAll()

                        // Static assets (CSS stylesheets and images) are always served from
                        // the monolith and require no authentication.
                        .matchers(ciMatcher("/css/**", "/images/**")).permitAll()

                        // Authentication (sign-on) endpoint must be publicly accessible —
                        // users need to reach this endpoint to obtain a JWT token.
                        .matchers(ciMatcher("/api/accounts/signon")).permitAll()

                        // Registration endpoint must be publicly accessible — new users
                        // need to create accounts without prior authentication.
                        .matchers(ciMatcher(HttpMethod.POST, "/api/accounts")).permitAll()

                        // Actuator endpoints for health checks, info, and readiness probes
                        // used by container orchestration and monitoring infrastructure.
                        .matchers(ciMatcher("/actuator/**")).permitAll()

                        // ================================================================
                        // PROTECTED PATHS — Require JWT authentication
                        // ================================================================

                        // All order operations require authentication. This mirrors the
                        // monolith's OrderActionBean behavior:
                        //
                        // newOrderForm() (line 125):
                        //   if (accountBean == null || !accountBean.isAuthenticated()) {
                        //       setMessage("You must sign on before attempting to check out...");
                        //       return new ForwardResolution(AccountActionBean.class);
                        //   }
                        //
                        // listOrders() (line 109-110):
                        //   AccountActionBean accountBean = (AccountActionBean)
                        //       session.getAttribute("/actions/Account.action");
                        //   orderList = orderService.getOrdersByUsername(
                        //       accountBean.getAccount().getUsername());
                        //
                        // viewOrder() (line 174):
                        //   AccountActionBean accountBean = (AccountActionBean)
                        //       session.getAttribute("accountBean");
                        .matchers(ciMatcher("/actions/Order.action**")).authenticated()

                        // REST API order operations require authentication — mirrors the
                        // same protection applied to the monolith's order ActionBean.
                        .matchers(ciMatcher("/api/orders/**")).authenticated()

                        // Account access by username (GET) requires authentication —
                        // users may only view their own profile data.
                        .matchers(ciMatcher(HttpMethod.GET, "/api/accounts/{username}")).authenticated()

                        // Account update by username (PUT) requires authentication —
                        // users may only modify their own account information.
                        .matchers(ciMatcher(HttpMethod.PUT, "/api/accounts/{username}")).authenticated()

                        // ================================================================
                        // DEFAULT — Permit all unmatched exchanges
                        // ================================================================
                        // During the Strangler Fig coexistence period, the monolith handles
                        // its own authentication for any paths not explicitly listed above.
                        // This includes the root path (/), index.html, help.html, and any
                        // other monolith-served content that is routed through the gateway.
                        .anyExchange().permitAll()
                )

                // Configure exception handling to return plain 401 Unauthorized without
                // any WWW-Authenticate challenge header. The gateway uses JWT tokens
                // exclusively — there is no browser-based authentication challenge.
                // Without this explicit entry point, Spring Security's default
                // HttpBasicServerAuthenticationEntryPoint would add a
                // "WWW-Authenticate: Basic realm=..." header on every 401 response.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(
                                new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                )

                // Disable HTTP basic authentication — the gateway uses JWT tokens
                // exclusively for authentication, not HTTP basic credentials.
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)

                // Disable form login — the gateway is a stateless API router, not a
                // login UI. Authentication is handled via JWT tokens issued by the
                // Account Service's /api/accounts/signon endpoint.
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                .build();
    }

    // -------------------------------------------------------------------------
    // CORS Configuration
    // -------------------------------------------------------------------------

    /**
     * Configures CORS for the API Gateway to allow browser-based cross-origin API calls.
     *
     * <p>During the Strangler Fig coexistence period, browser clients may make API calls
     * from the monolith's origin (e.g., {@code http://localhost:8080}) to the new
     * microservice endpoints proxied through the gateway. CORS headers ensure these
     * cross-origin requests succeed, including OPTIONS preflight requests.</p>
     *
     * <p>The configuration is permissive for development and should be tightened
     * for production deployment by restricting allowed origins to specific domains.</p>
     *
     * @return the configured {@link CorsConfigurationSource} for the gateway
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration corsConfig = new CorsConfiguration();

        // Allow all origins during development/coexistence — tighten for production
        corsConfig.addAllowedOriginPattern("*");

        // Allow all standard HTTP methods used by the microservices REST APIs
        corsConfig.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"
        ));

        // Allow all headers — includes Authorization (JWT), Content-Type, and custom headers
        corsConfig.addAllowedHeader("*");

        // Expose response headers that clients may need to read
        corsConfig.setExposedHeaders(Arrays.asList(
                "Authorization", "X-Auth-Username", "X-Auth-AccountId"
        ));

        // Allow credentials (cookies, Authorization header) for JWT cookie support
        // (AAP Section 0.7.2: JWT stored as HTTP-only cookie for monolith JSP pages)
        corsConfig.setAllowCredentials(true);

        // Cache preflight responses for 1 hour to reduce OPTIONS request overhead
        corsConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);
        return source;
    }

    // -------------------------------------------------------------------------
    // Case-Insensitive Path Matching Utilities
    // -------------------------------------------------------------------------

    /**
     * Creates a case-insensitive {@link ServerWebExchangeMatcher} for the given path patterns.
     *
     * <p>Uses {@link PathPatternParser} with {@code setCaseSensitive(false)} to ensure
     * security rules cannot be bypassed by varying the case of URL paths. Without this,
     * a request to {@code /ACTIONS/ORDER.ACTION} would not match the protected pattern
     * {@code /actions/Order.action**} and would fall through to the default
     * {@code anyExchange().permitAll()} rule.</p>
     *
     * <p>Matches any HTTP method. For method-specific matching, use
     * {@link #ciMatcher(HttpMethod, String...)}.</p>
     *
     * @param patterns one or more path patterns using Spring path pattern syntax
     * @return a case-insensitive exchange matcher
     */
    private static ServerWebExchangeMatcher ciMatcher(String... patterns) {
        return ciMatcher(null, patterns);
    }

    /**
     * Creates a case-insensitive {@link ServerWebExchangeMatcher} for the given HTTP method
     * and path patterns.
     *
     * <p>When {@code method} is non-null, the matcher only matches requests with the
     * specified HTTP method AND a case-insensitive path match. When {@code method} is null,
     * only the path is checked (any HTTP method matches).</p>
     *
     * @param method   the HTTP method to match, or {@code null} for any method
     * @param patterns one or more path patterns using Spring path pattern syntax
     * @return a case-insensitive exchange matcher with optional method restriction
     */
    private static ServerWebExchangeMatcher ciMatcher(HttpMethod method, String... patterns) {
        PathPatternParser parser = new PathPatternParser();
        parser.setCaseSensitive(false);
        List<PathPattern> pathPatterns = Arrays.stream(patterns)
                .map(parser::parse)
                .toList();

        return exchange -> {
            // Check HTTP method if specified
            if (method != null && !exchange.getRequest().getMethod().equals(method)) {
                return ServerWebExchangeMatcher.MatchResult.notMatch();
            }
            // Check path against all patterns (case-insensitive)
            PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
            for (PathPattern pathPattern : pathPatterns) {
                if (pathPattern.matches(path)) {
                    return ServerWebExchangeMatcher.MatchResult.match();
                }
            }
            return ServerWebExchangeMatcher.MatchResult.notMatch();
        };
    }
}
