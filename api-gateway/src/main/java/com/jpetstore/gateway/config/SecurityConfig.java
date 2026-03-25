package com.jpetstore.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

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
 * <h3>JWT Validation Delegation</h3>
 * <p>JWT token validation is NOT performed here — it is delegated to the
 * {@code AuthenticationFilter} (a Spring Cloud Gateway {@code GlobalFilter} in the
 * {@code filter} package). This class only defines the authorization rules that determine
 * which paths require authentication and which are publicly accessible.
 *
 * @see org.springframework.security.config.web.server.ServerHttpSecurity
 * @see org.springframework.security.web.server.SecurityWebFilterChain
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

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

                // Define path-based authorization rules using reactive exchange matchers
                .authorizeExchange(exchanges -> exchanges

                        // ================================================================
                        // PUBLIC PATHS — No authentication required
                        // ================================================================

                        // Catalog browsing is fully public. CatalogActionBean has zero
                        // authentication checks in its source code — all methods
                        // (viewCategory, viewProduct, viewItem, searchProducts) are public.
                        .pathMatchers("/actions/Catalog.action**").permitAll()

                        // Account action paths are publicly accessible during coexistence.
                        // The Stripes framework routes all account operations (signonForm,
                        // signon, newAccountForm, newAccount, editAccountForm, editAccount)
                        // through /actions/Account.action with different _eventName params.
                        // The monolith's session-scoped AccountActionBean handles its own
                        // authentication checks for edit operations internally.
                        .pathMatchers("/actions/Account.action**").permitAll()

                        // Cart operations are public — unauthenticated users can browse and
                        // build a cart before signing in. This preserves the monolith's
                        // existing behavior (AAP Section 0.7.2: "Unauthenticated users can
                        // browse and build a cart before signing in").
                        .pathMatchers("/actions/Cart.action**").permitAll()

                        // Static assets (CSS stylesheets and images) are always served from
                        // the monolith and require no authentication.
                        .pathMatchers("/css/**", "/images/**").permitAll()

                        // Authentication (sign-on) endpoint must be publicly accessible —
                        // users need to reach this endpoint to obtain a JWT token.
                        .pathMatchers("/api/accounts/signon").permitAll()

                        // Registration endpoint must be publicly accessible — new users
                        // need to create accounts without prior authentication.
                        .pathMatchers(HttpMethod.POST, "/api/accounts").permitAll()

                        // Actuator endpoints for health checks, info, and readiness probes
                        // used by container orchestration and monitoring infrastructure.
                        .pathMatchers("/actuator/**").permitAll()

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
                        .pathMatchers("/actions/Order.action**").authenticated()

                        // REST API order operations require authentication — mirrors the
                        // same protection applied to the monolith's order ActionBean.
                        .pathMatchers("/api/orders/**").authenticated()

                        // Account access by username (GET) requires authentication —
                        // users may only view their own profile data.
                        .pathMatchers(HttpMethod.GET, "/api/accounts/{username}").authenticated()

                        // Account update by username (PUT) requires authentication —
                        // users may only modify their own account information.
                        .pathMatchers(HttpMethod.PUT, "/api/accounts/{username}").authenticated()

                        // ================================================================
                        // DEFAULT — Permit all unmatched exchanges
                        // ================================================================
                        // During the Strangler Fig coexistence period, the monolith handles
                        // its own authentication for any paths not explicitly listed above.
                        // This includes the root path (/), index.html, help.html, and any
                        // other monolith-served content that is routed through the gateway.
                        .anyExchange().permitAll()
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
}
