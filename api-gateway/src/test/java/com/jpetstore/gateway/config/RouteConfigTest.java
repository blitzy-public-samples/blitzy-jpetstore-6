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

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Integration tests for {@link RouteConfig} verifying all Spring Cloud Gateway
 * route definitions for the JPetStore Strangler Fig architecture.
 *
 * <p>This test class validates that all 11 gateway routes are correctly configured:</p>
 * <ul>
 *   <li>4 flag-controlled Strangler Fig routes ({@code *.action} patterns defaulting to
 *       monolith with metadata for runtime switching to microservices)</li>
 *   <li>4 static microservice API routes (always route to microservices)</li>
 *   <li>2 static asset routes (always route to monolith)</li>
 *   <li>1 catch-all fallback route (always route to monolith)</li>
 * </ul>
 *
 * <p>The test uses {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} to load the
 * full reactive application context including all configuration classes and filters.
 * {@link ReactiveRedisConnectionFactory} is mocked to avoid requiring a running Redis
 * instance during tests.</p>
 *
 * <p><strong>Reactive Testing</strong>: All route verification uses {@link StepVerifier}
 * and reactive operators on {@link Flux} — no servlet APIs are used since Spring Cloud
 * Gateway runs on Netty/WebFlux.</p>
 *
 * @see RouteConfig
 * @see com.jpetstore.gateway.filter.RoutingFlagFilter
 * @see com.jpetstore.gateway.filter.AuthenticationFilter
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
class RouteConfigTest {

    /**
     * Mock Redis connection factory to prevent the test from requiring a running
     * Redis instance. The {@link RoutingFlagConfig} creates a
     * {@link org.springframework.data.redis.core.ReactiveRedisTemplate} from this
     * factory, and the {@link RoutingFlagConfig.RoutingFlagService} uses it to read
     * routing flag values from Redis. Mocking the connection factory allows the full
     * application context to initialize without actual Redis connectivity.
     *
     * <p>Redis auto-configuration ({@code RedisAutoConfiguration},
     * {@code RedisReactiveAutoConfiguration}, and {@code GatewayRedisAutoConfiguration})
     * is excluded via {@code spring.autoconfigure.exclude} to prevent:
     * <ul>
     *   <li>The synchronous {@code RedisTemplate} from requiring a non-reactive
     *       {@code RedisConnectionFactory} (type mismatch with this mock)</li>
     *   <li>Duplicate {@code ReactiveRedisTemplate} bean conflict with the custom
     *       bean defined in {@link RoutingFlagConfig}</li>
     *   <li>Gateway Redis rate limiter auto-configuration requiring Redis</li>
     * </ul>
     */
    @MockitoBean
    private ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    /**
     * The composite {@link RouteLocator} injected from the application context.
     * Contains all routes defined by {@link RouteConfig#gatewayRouteLocator}.
     * Used for programmatic route verification via {@code getRoutes()} which returns
     * a {@link Flux} of {@link Route} instances.
     */
    @Autowired
    private RouteLocator routeLocator;

    /**
     * Auto-configured reactive {@link WebTestClient} for the running gateway server.
     * Available due to {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} with
     * the reactive Netty web server provided by Spring Cloud Gateway.
     */
    @Autowired
    private WebTestClient webTestClient;

    // =========================================================================
    // Flag-Controlled Strangler Fig Routes
    // =========================================================================
    // These routes map the monolith's Stripes *.action URL patterns to backend
    // services. By default, all traffic goes to the monolith. Each route carries
    // metadata with a Redis key (routing-flag-key) and a microservice URI
    // (microservice-uri) that the RoutingFlagFilter reads at runtime to optionally
    // redirect traffic to the corresponding microservice.
    // =========================================================================

    /**
     * Verifies the {@code account-actions} route is correctly configured for the
     * Account bounded context Strangler Fig routing.
     *
     * <p>Expected configuration:</p>
     * <ul>
     *   <li>Route ID: {@code account-actions}</li>
     *   <li>Default URI: monolith ({@code http://monolith:8080})</li>
     *   <li>Routing flag key: {@code routing.flag.account-service}</li>
     *   <li>Microservice URI: {@code http://account-service:8081}</li>
     * </ul>
     */
    @Test
    void accountActionRouteShouldHaveCorrectMetadata() {
        // given
        Flux<Route> accountRoutes = routeLocator.getRoutes()
                .filter(route -> "account-actions".equals(route.getId()));

        // when / then
        StepVerifier.create(accountRoutes.next())
                .expectNextMatches(route -> {
                    // Route defaults to monolith
                    assertThat(route.getUri().toString()).isEqualTo("http://monolith:8080");

                    // Verify Strangler Fig metadata for runtime flag switching
                    Map<String, Object> metadata = route.getMetadata();
                    assertThat(metadata).isNotNull();
                    assertThat(metadata).containsKey("routing-flag-key");
                    assertThat(metadata.get("routing-flag-key"))
                            .isEqualTo("routing.flag.account-service");
                    assertThat(metadata).containsKey("microservice-uri");
                    assertThat(metadata.get("microservice-uri"))
                            .isEqualTo("http://account-service:8081");
                    return true;
                })
                .verifyComplete();
    }

    /**
     * Verifies the {@code catalog-actions} route is correctly configured for the
     * Catalog bounded context Strangler Fig routing.
     *
     * <p>Expected configuration:</p>
     * <ul>
     *   <li>Route ID: {@code catalog-actions}</li>
     *   <li>Default URI: monolith ({@code http://monolith:8080})</li>
     *   <li>Routing flag key: {@code routing.flag.catalog-service}</li>
     *   <li>Microservice URI: {@code http://catalog-service:8082}</li>
     * </ul>
     */
    @Test
    void catalogActionRouteShouldHaveCorrectMetadata() {
        // given
        Flux<Route> catalogRoutes = routeLocator.getRoutes()
                .filter(route -> "catalog-actions".equals(route.getId()));

        // when / then
        StepVerifier.create(catalogRoutes.next())
                .expectNextMatches(route -> {
                    // Route defaults to monolith
                    assertThat(route.getUri().toString()).isEqualTo("http://monolith:8080");

                    // Verify Strangler Fig metadata
                    Map<String, Object> metadata = route.getMetadata();
                    assertThat(metadata).isNotNull();
                    assertThat(metadata).containsKey("routing-flag-key");
                    assertThat(metadata.get("routing-flag-key"))
                            .isEqualTo("routing.flag.catalog-service");
                    assertThat(metadata).containsKey("microservice-uri");
                    assertThat(metadata.get("microservice-uri"))
                            .isEqualTo("http://catalog-service:8082");
                    return true;
                })
                .verifyComplete();
    }

    /**
     * Verifies the {@code cart-actions} route uses the Order Service routing flag,
     * confirming that Cart is part of the Order bounded context.
     *
     * <p>Per AAP Section 0.1.1, Cart and Order share the same bounded context
     * (Order/Cart). Both {@code cart-actions} and {@code order-actions} routes
     * use the {@code routing.flag.order-service} flag key so they are cut over
     * together as a single unit.</p>
     *
     * <p>Expected configuration:</p>
     * <ul>
     *   <li>Route ID: {@code cart-actions}</li>
     *   <li>Default URI: monolith ({@code http://monolith:8080})</li>
     *   <li>Routing flag key: {@code routing.flag.order-service} (shared with Order)</li>
     *   <li>Microservice URI: {@code http://order-service:8083}</li>
     * </ul>
     */
    @Test
    void cartActionRouteShouldUseOrderServiceFlag() {
        // given — Cart is part of the Order bounded context (AAP Section 0.1.1)
        Flux<Route> cartRoutes = routeLocator.getRoutes()
                .filter(route -> "cart-actions".equals(route.getId()));

        // when / then
        StepVerifier.create(cartRoutes.next())
                .expectNextMatches(route -> {
                    // Route defaults to monolith
                    assertThat(route.getUri().toString()).isEqualTo("http://monolith:8080");

                    // Cart shares the Order Service routing flag — both are cut over together
                    Map<String, Object> metadata = route.getMetadata();
                    assertThat(metadata).isNotNull();
                    assertThat(metadata).containsKey("routing-flag-key");
                    assertThat(metadata.get("routing-flag-key"))
                            .isEqualTo("routing.flag.order-service");
                    assertThat(metadata).containsKey("microservice-uri");
                    assertThat(metadata.get("microservice-uri"))
                            .isEqualTo("http://order-service:8083");
                    return true;
                })
                .verifyComplete();
    }

    /**
     * Verifies the {@code order-actions} route is correctly configured for the
     * Order bounded context Strangler Fig routing.
     *
     * <p>Expected configuration:</p>
     * <ul>
     *   <li>Route ID: {@code order-actions}</li>
     *   <li>Default URI: monolith ({@code http://monolith:8080})</li>
     *   <li>Routing flag key: {@code routing.flag.order-service}</li>
     *   <li>Microservice URI: {@code http://order-service:8083}</li>
     * </ul>
     */
    @Test
    void orderActionRouteShouldHaveCorrectMetadata() {
        // given
        Flux<Route> orderRoutes = routeLocator.getRoutes()
                .filter(route -> "order-actions".equals(route.getId()));

        // when / then
        StepVerifier.create(orderRoutes.next())
                .expectNextMatches(route -> {
                    // Route defaults to monolith
                    assertThat(route.getUri().toString()).isEqualTo("http://monolith:8080");

                    // Verify Strangler Fig metadata
                    Map<String, Object> metadata = route.getMetadata();
                    assertThat(metadata).isNotNull();
                    assertThat(metadata).containsKey("routing-flag-key");
                    assertThat(metadata.get("routing-flag-key"))
                            .isEqualTo("routing.flag.order-service");
                    assertThat(metadata).containsKey("microservice-uri");
                    assertThat(metadata.get("microservice-uri"))
                            .isEqualTo("http://order-service:8083");
                    return true;
                })
                .verifyComplete();
    }

    // =========================================================================
    // Static Microservice API Routes (Always Microservice)
    // =========================================================================
    // REST APIs exposed by the new microservices always route directly to their
    // respective microservice — no routing flag evaluation is needed because
    // these endpoints only exist on the microservices (not the monolith).
    // =========================================================================

    /**
     * Verifies the {@code account-api} route always targets the Account Service
     * directly without flag evaluation.
     *
     * <p>The {@code /api/accounts/**} path pattern only exists on the Account
     * microservice. No routing flag metadata should be present since this route
     * is not subject to Strangler Fig flag switching.</p>
     */
    @Test
    void accountApiRouteShouldAlwaysRouteToAccountService() {
        // given
        Flux<Route> apiRoutes = routeLocator.getRoutes()
                .filter(route -> "account-api".equals(route.getId()));

        // when / then
        StepVerifier.create(apiRoutes.next())
                .expectNextMatches(route -> {
                    // Always routes to account service — not flag-controlled
                    assertThat(route.getUri().toString())
                            .isEqualTo("http://account-service:8081");
                    // No routing flag metadata — always microservice
                    assertThat(route.getMetadata()).doesNotContainKey("routing-flag-key");
                    return true;
                })
                .verifyComplete();
    }

    /**
     * Verifies the {@code catalog-api} route always targets the Catalog Service
     * directly without flag evaluation.
     *
     * <p>The {@code /api/catalog/**} path pattern only exists on the Catalog
     * microservice. No routing flag metadata should be present.</p>
     */
    @Test
    void catalogApiRouteShouldAlwaysRouteToCatalogService() {
        // given
        Flux<Route> apiRoutes = routeLocator.getRoutes()
                .filter(route -> "catalog-api".equals(route.getId()));

        // when / then
        StepVerifier.create(apiRoutes.next())
                .expectNextMatches(route -> {
                    // Always routes to catalog service — not flag-controlled
                    assertThat(route.getUri().toString())
                            .isEqualTo("http://catalog-service:8082");
                    // No routing flag metadata — always microservice
                    assertThat(route.getMetadata()).doesNotContainKey("routing-flag-key");
                    return true;
                })
                .verifyComplete();
    }

    /**
     * Verifies the {@code order-api} route always targets the Order Service
     * directly without flag evaluation.
     *
     * <p>The {@code /api/orders/**} path pattern only exists on the Order
     * microservice. No routing flag metadata should be present.</p>
     */
    @Test
    void orderApiRouteShouldAlwaysRouteToOrderService() {
        // given
        Flux<Route> apiRoutes = routeLocator.getRoutes()
                .filter(route -> "order-api".equals(route.getId()));

        // when / then
        StepVerifier.create(apiRoutes.next())
                .expectNextMatches(route -> {
                    // Always routes to order service — not flag-controlled
                    assertThat(route.getUri().toString())
                            .isEqualTo("http://order-service:8083");
                    // No routing flag metadata — always microservice
                    assertThat(route.getMetadata()).doesNotContainKey("routing-flag-key");
                    return true;
                })
                .verifyComplete();
    }

    // =========================================================================
    // Static Asset Routes (Always Monolith)
    // =========================================================================
    // CSS and images always come from the monolith. Static assets are explicitly
    // out of scope for migration (AAP Section 0.3.2).
    // =========================================================================

    /**
     * Verifies the {@code static-css} route always targets the monolith for
     * serving CSS static assets.
     *
     * <p>Per AAP Section 0.3.2, static assets are explicitly out of scope for
     * migration. The {@code /css/**} path always routes to the monolith.</p>
     */
    @Test
    void cssRouteShouldAlwaysRouteToMonolith() {
        // given
        Flux<Route> cssRoutes = routeLocator.getRoutes()
                .filter(route -> "static-css".equals(route.getId()));

        // when / then
        StepVerifier.create(cssRoutes.next())
                .expectNextMatches(route -> {
                    // Static assets always served from monolith
                    assertThat(route.getUri().toString())
                            .isEqualTo("http://monolith:8080");
                    return true;
                })
                .verifyComplete();
    }

    /**
     * Verifies the {@code static-images} route always targets the monolith for
     * serving image static assets.
     *
     * <p>Per AAP Section 0.3.2, static assets are explicitly out of scope for
     * migration. The {@code /images/**} path always routes to the monolith.</p>
     */
    @Test
    void imagesRouteShouldAlwaysRouteToMonolith() {
        // given
        Flux<Route> imageRoutes = routeLocator.getRoutes()
                .filter(route -> "static-images".equals(route.getId()));

        // when / then
        StepVerifier.create(imageRoutes.next())
                .expectNextMatches(route -> {
                    // Static assets always served from monolith
                    assertThat(route.getUri().toString())
                            .isEqualTo("http://monolith:8080");
                    return true;
                })
                .verifyComplete();
    }

    // =========================================================================
    // Catch-All Monolith Fallback Route
    // =========================================================================
    // Any request not matching the above patterns falls through to the monolith.
    // This handles: /, index.html, help.html, and any unmatched paths.
    // =========================================================================

    /**
     * Verifies the {@code monolith-fallback} catch-all route exists, targets the
     * monolith, and has the lowest priority (order 9999) to ensure it is evaluated
     * after all more specific routes.
     *
     * <p>This route ensures that any unmatched request during the Strangler Fig
     * coexistence period is safely routed to the monolith, which continues to
     * serve all legacy paths (e.g., {@code /}, {@code index.html}, {@code help.html}).</p>
     */
    @Test
    void fallbackRouteShouldExist() {
        // given
        Flux<Route> fallbackRoutes = routeLocator.getRoutes()
                .filter(route -> "monolith-fallback".equals(route.getId()));

        // when / then
        StepVerifier.create(fallbackRoutes.next())
                .expectNextMatches(route -> {
                    // Fallback always targets monolith
                    assertThat(route.getUri().toString())
                            .isEqualTo("http://monolith:8080");
                    // Lowest priority — evaluated last after all specific routes
                    assertThat(route.getOrder()).isEqualTo(9999);
                    return true;
                })
                .verifyComplete();
    }

    // =========================================================================
    // Comprehensive Route Inventory Verification
    // =========================================================================

    /**
     * Verifies that all expected routes are defined and no extra routes exist.
     *
     * <p>Expected route inventory (11 total):</p>
     * <ul>
     *   <li>4 flag-controlled Strangler Fig routes: {@code account-actions},
     *       {@code catalog-actions}, {@code cart-actions}, {@code order-actions}</li>
     *   <li>4 static microservice API routes: {@code account-api},
     *       {@code catalog-api}, {@code order-api}, {@code cart-api}</li>
     *   <li>2 static asset routes: {@code static-css}, {@code static-images}</li>
     *   <li>1 catch-all fallback: {@code monolith-fallback}</li>
     * </ul>
     */
    @Test
    void allExpectedRoutesShouldBeDefined() {
        // given / when
        StepVerifier.create(routeLocator.getRoutes().collectList())
                // then
                .expectNextMatches(routes -> {
                    // Verify total route count:
                    // 4 flag-controlled + 4 API + 2 static + 1 catch-all = 11
                    assertThat(routes).hasSize(11);

                    // Verify all expected route IDs are present
                    List<String> routeIds = routes.stream()
                            .map(Route::getId)
                            .toList();

                    assertThat(routeIds).containsExactlyInAnyOrder(
                            // Flag-controlled Strangler Fig routes (*.action patterns)
                            "account-actions",
                            "catalog-actions",
                            "cart-actions",
                            "order-actions",
                            // Static microservice API routes (/api/** patterns)
                            "account-api",
                            "catalog-api",
                            "order-api",
                            "cart-api",
                            // Static asset routes (CSS and images)
                            "static-css",
                            "static-images",
                            // Catch-all fallback (/** pattern)
                            "monolith-fallback"
                    );
                    return true;
                })
                .verifyComplete();
    }
}
