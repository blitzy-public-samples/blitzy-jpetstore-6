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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic Spring Cloud Gateway route definitions implementing the Strangler Fig
 * pattern for the JPetStore monolith-to-microservices decomposition.
 *
 * <p>This configuration class replaces the monolith's Stripes {@code DispatcherServlet}
 * URL mapping ({@code *.action} pattern from {@code web.xml}) with a gateway-based
 * routing layer that can progressively redirect traffic from the monolith to individual
 * microservices at runtime, without requiring redeployment.</p>
 *
 * <h3>Route Categories</h3>
 * <ol>
 *   <li><strong>Flag-controlled Strangler Fig routes</strong> — {@code *.action} URLs that
 *       default to the monolith. Each route carries metadata with a Redis key
 *       ({@code routing-flag-key}) and the target microservice URI ({@code microservice-uri}).
 *       At runtime, {@code RoutingFlagFilter} reads the routing flag from Redis and can
 *       override the target from the monolith to the microservice.</li>
 *   <li><strong>Static microservice API routes</strong> — {@code /api/**} paths that always
 *       route directly to their owning microservice (no flag evaluation).</li>
 *   <li><strong>Static asset routes</strong> — {@code /css/**} and {@code /images/**} paths
 *       that always route to the monolith (static assets are not migrated).</li>
 *   <li><strong>Catch-all fallback route</strong> — {@code /**} pattern at lowest priority
 *       (order 9999) that routes unmatched requests to the monolith.</li>
 * </ol>
 *
 * <h3>Bounded Context Mapping</h3>
 * <table>
 *   <tr><th>ActionBean</th><th>URL Pattern</th><th>Bounded Context</th><th>Routing Flag Key</th></tr>
 *   <tr><td>AccountActionBean</td><td>/actions/Account.action**</td><td>Account</td><td>routing.flag.account-service</td></tr>
 *   <tr><td>CatalogActionBean</td><td>/actions/Catalog.action**</td><td>Catalog</td><td>routing.flag.catalog-service</td></tr>
 *   <tr><td>CartActionBean</td><td>/actions/Cart.action**</td><td>Order</td><td>routing.flag.order-service</td></tr>
 *   <tr><td>OrderActionBean</td><td>/actions/Order.action**</td><td>Order</td><td>routing.flag.order-service</td></tr>
 * </table>
 *
 * <h3>Service Cutover Order</h3>
 * <p>Catalog → Account → Order. Routing flags are switched one at a time in Redis
 * from {@code "monolith"} to {@code "microservice"} — no redeployment required.</p>
 *
 * <p><strong>CRITICAL</strong>: Spring Cloud Gateway runs on Netty/WebFlux. This class
 * uses only reactive-compatible constructs. No servlet APIs are imported.</p>
 *
 * @see com.jpetstore.gateway.filter.RoutingFlagFilter
 */
@Configuration
public class RouteConfig {

    /**
     * Base URL of the legacy monolith service.
     * Default uses Docker Compose service name {@code monolith} on port 8080.
     * Overridable via the {@code MONOLITH_URL} environment variable.
     */
    @Value("${services.monolith.url:http://monolith:8080}")
    private String monolithUrl;

    /**
     * Base URL of the Account microservice.
     * Default uses Docker Compose service name {@code account-service} on port 8081.
     * Overridable via the {@code ACCOUNT_SERVICE_URL} environment variable.
     */
    @Value("${services.account.url:http://account-service:8081}")
    private String accountServiceUrl;

    /**
     * Base URL of the Catalog microservice.
     * Default uses Docker Compose service name {@code catalog-service} on port 8082.
     * Overridable via the {@code CATALOG_SERVICE_URL} environment variable.
     */
    @Value("${services.catalog.url:http://catalog-service:8082}")
    private String catalogServiceUrl;

    /**
     * Base URL of the Order microservice.
     * Default uses Docker Compose service name {@code order-service} on port 8083.
     * Overridable via the {@code ORDER_SERVICE_URL} environment variable.
     */
    @Value("${services.order.url:http://order-service:8083}")
    private String orderServiceUrl;

    /**
     * Defines all gateway routes for the JPetStore Strangler Fig architecture.
     *
     * <p>Routes are evaluated in declaration order for programmatic definitions.
     * More specific routes (action paths, API paths, static assets) are declared
     * before the catch-all {@code /**} fallback to ensure correct matching.</p>
     *
     * <p>Flag-controlled routes carry two metadata entries consumed by
     * {@code RoutingFlagFilter}:</p>
     * <ul>
     *   <li>{@code routing-flag-key} — the Redis key holding the routing flag
     *       value ({@code "monolith"} or {@code "microservice"})</li>
     *   <li>{@code microservice-uri} — the target microservice URL to use when
     *       the flag value is {@code "microservice"}</li>
     * </ul>
     *
     * @param builder the Spring Cloud Gateway route builder injected by the framework
     * @return a {@link RouteLocator} containing all 10 route definitions
     */
    @Bean
    public RouteLocator gatewayRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()

                // =================================================================
                // 1. FLAG-CONTROLLED STRANGLER FIG ROUTES
                // =================================================================
                // These routes map the Stripes *.action URL pattern (web.xml line 62)
                // to backend services. By default, all traffic goes to the monolith.
                // The RoutingFlagFilter reads metadata at runtime to optionally
                // redirect to the corresponding microservice when the Redis flag
                // value is "microservice".
                // =================================================================

                // Account bounded context — AccountActionBean
                // Handles: signon, signoff, newAccount, editAccount, newAccountForm,
                //          editAccountForm, signonForm
                // Routing flag: routing.flag.account-service
                .route("account-actions", r -> r
                        .path("/actions/Account.action**")
                        .metadata("routing-flag-key", "routing.flag.account-service")
                        .metadata("microservice-uri", accountServiceUrl)
                        .uri(monolithUrl))

                // Catalog bounded context — CatalogActionBean
                // Handles: viewMain, viewCategory, viewProduct, viewItem, searchProducts
                // Routing flag: routing.flag.catalog-service
                .route("catalog-actions", r -> r
                        .path("/actions/Catalog.action**")
                        .metadata("routing-flag-key", "routing.flag.catalog-service")
                        .metadata("microservice-uri", catalogServiceUrl)
                        .uri(monolithUrl))

                // Order bounded context (Cart) — CartActionBean
                // Handles: addItemToCart, removeItemFromCart, updateCartQuantities,
                //          viewCart, checkOut
                // Cart is part of the Order bounded context (AAP Section 0.1.1)
                // Routing flag: routing.flag.order-service (shared with Order routes)
                .route("cart-actions", r -> r
                        .path("/actions/Cart.action**")
                        .metadata("routing-flag-key", "routing.flag.order-service")
                        .metadata("microservice-uri", orderServiceUrl)
                        .uri(monolithUrl))

                // Order bounded context (Order) — OrderActionBean
                // Handles: newOrderForm, newOrder, listOrders, viewOrder
                // Routing flag: routing.flag.order-service (shared with Cart routes)
                .route("order-actions", r -> r
                        .path("/actions/Order.action**")
                        .metadata("routing-flag-key", "routing.flag.order-service")
                        .metadata("microservice-uri", orderServiceUrl)
                        .uri(monolithUrl))

                // =================================================================
                // 2. STATIC MICROSERVICE API ROUTES
                // =================================================================
                // REST APIs exposed by the new microservices. These always route
                // directly to their respective microservice — no routing flag
                // evaluation is needed because these endpoints only exist on the
                // microservices (they are not served by the monolith).
                // =================================================================

                // Account Service REST API
                // Endpoints: POST /api/accounts/signon, POST /api/accounts,
                //            PUT /api/accounts/{username}, GET /api/accounts/{username}
                .route("account-api", r -> r
                        .path("/api/accounts/**")
                        .uri(accountServiceUrl))

                // Catalog Service REST API
                // Endpoints: GET /api/catalog/categories, GET /api/catalog/products,
                //            GET /api/catalog/items, POST /api/catalog/items/{id}/inventory/decrement
                .route("catalog-api", r -> r
                        .path("/api/catalog/**")
                        .uri(catalogServiceUrl))

                // Order Service REST API
                // Endpoints: POST /api/orders, GET /api/orders?username=,
                //            GET /api/orders/{id}
                .route("order-api", r -> r
                        .path("/api/orders/**")
                        .uri(orderServiceUrl))

                // =================================================================
                // 3. STATIC ASSET ROUTES
                // =================================================================
                // CSS and images always come from the monolith. Static assets are
                // explicitly out of scope for migration (AAP Section 0.3.2).
                // =================================================================

                // CSS static assets (jpetstore.css and any future CSS files)
                .route("static-css", r -> r
                        .path("/css/**")
                        .uri(monolithUrl))

                // Image static assets (category banners, pet images, etc.)
                .route("static-images", r -> r
                        .path("/images/**")
                        .uri(monolithUrl))

                // =================================================================
                // 4. CATCH-ALL MONOLITH FALLBACK
                // =================================================================
                // Any request not matching the above patterns falls through to the
                // monolith. This handles: /, index.html, help.html, and any other
                // unmatched paths. The order(9999) ensures this is the lowest-
                // priority route and is evaluated last.
                // =================================================================
                .route("monolith-fallback", r -> r
                        .order(9999)
                        .path("/**")
                        .uri(monolithUrl))

                .build();
    }
}
