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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jpetstore.gateway.config.RoutingFlagConfig;

import java.net.URI;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link RoutingFlagFilter} — the Strangler Fig pattern's runtime routing
 * {@code GlobalFilter} in the JPetStore API Gateway.
 *
 * <p>This filter is the <b>core mechanism</b> controlling progressive traffic migration
 * from the monolith to individual microservices during the decomposition. It reads
 * per-service routing flags from Redis (via {@link RoutingFlagConfig.RoutingFlagService})
 * and dynamically switches request routing between the monolith and microservices.</p>
 *
 * <h3>Test Strategy</h3>
 * <p>Tests verify the complete routing decision matrix:</p>
 * <ul>
 *   <li>Flag = "monolith" → request passes through to default route (monolith URI)</li>
 *   <li>Flag = "microservice" → {@code GATEWAY_REQUEST_URL_ATTR} overridden with
 *       microservice URI while preserving original request path and query</li>
 *   <li>No routing flag metadata → passes through unchanged (static/API routes)</li>
 *   <li>Redis error → safe fallback to monolith (graceful degradation)</li>
 *   <li>Invalid microservice URI → 503 Service Unavailable response</li>
 *   <li>Filter order = 0 → runs after AuthenticationFilter (-100), before
 *       RouteToRequestUrlFilter (10000)</li>
 * </ul>
 *
 * <h3>Routing Table Coverage (AAP Section 0.7.6)</h3>
 * <table>
 *   <tr><th>URL Pattern</th><th>Flag Key</th><th>Microservice Target</th></tr>
 *   <tr><td>/actions/Account.action*</td><td>routing.flag.account-service</td>
 *       <td>http://account-service:8081</td></tr>
 *   <tr><td>/actions/Catalog.action*</td><td>routing.flag.catalog-service</td>
 *       <td>http://catalog-service:8082</td></tr>
 *   <tr><td>/actions/Cart.action*</td><td>routing.flag.order-service</td>
 *       <td>http://order-service:8083</td></tr>
 *   <tr><td>/actions/Order.action*</td><td>routing.flag.order-service</td>
 *       <td>http://order-service:8083</td></tr>
 * </table>
 *
 * <p>Test patterns follow the existing codebase conventions:</p>
 * <ul>
 *   <li>Package-private class (no {@code public} modifier) — matches
 *       {@code AccountServiceTest}, {@code AuthenticationFilterTest}</li>
 *   <li>Given/When/Then comment style — matches {@code AccountServiceTest}</li>
 *   <li>AssertJ {@code assertThat()} — matches all existing tests</li>
 *   <li>{@code @ExtendWith(MockitoExtension.class)} — matches existing pattern</li>
 *   <li>{@code StepVerifier} for reactive assertions — matches
 *       {@code AuthenticationFilterTest}</li>
 * </ul>
 *
 * @see RoutingFlagFilter
 * @see RoutingFlagConfig.RoutingFlagService
 * @see com.jpetstore.gateway.config.RouteConfig
 */
@ExtendWith(MockitoExtension.class)
class RoutingFlagFilterTest {

    // -------------------------------------------------------------------------
    // Test Constants — Service URIs (AAP Section 0.7.6 Routing Table)
    // -------------------------------------------------------------------------

    /** Default monolith target URI — used as the route's base URI for flag-controlled routes. */
    private static final String MONOLITH_URI = "http://monolith:8080";

    /** Account Service microservice target URI (port 8081). */
    private static final String ACCOUNT_SERVICE_URI = "http://account-service:8081";

    /** Catalog Service microservice target URI (port 8082). */
    private static final String CATALOG_SERVICE_URI = "http://catalog-service:8082";

    /** Order Service microservice target URI (port 8083) — also handles Cart routes. */
    private static final String ORDER_SERVICE_URI = "http://order-service:8083";

    // -------------------------------------------------------------------------
    // Test Constants — Redis Routing Flag Keys
    // -------------------------------------------------------------------------

    /** Redis key for the Account Service routing flag. */
    private static final String ACCOUNT_FLAG_KEY = "routing.flag.account-service";

    /** Redis key for the Catalog Service routing flag. */
    private static final String CATALOG_FLAG_KEY = "routing.flag.catalog-service";

    /** Redis key for the Order Service routing flag (covers both Order and Cart). */
    private static final String ORDER_FLAG_KEY = "routing.flag.order-service";

    // -------------------------------------------------------------------------
    // Mocks and Subject Under Test
    // -------------------------------------------------------------------------

    /**
     * Mock GatewayFilterChain — simulates the downstream filter chain.
     * Returns {@code Mono.empty()} to indicate successful downstream processing.
     */
    @Mock
    private GatewayFilterChain filterChain;

    /**
     * Mock RoutingFlagService — replaces the real Redis-backed service, enabling
     * tests to run without Redis by controlling {@code getFlag()} return values.
     */
    @Mock
    private RoutingFlagConfig.RoutingFlagService routingFlagService;

    /**
     * The RoutingFlagFilter instance under test, constructed directly with the
     * mocked RoutingFlagService (constructor injection).
     */
    private RoutingFlagFilter routingFlagFilter;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    /**
     * Initializes the RoutingFlagFilter with the mocked RoutingFlagService and
     * configures the mock filter chain to return {@code Mono.empty()} for any exchange.
     *
     * <p>Uses {@code lenient()} for the filterChain stub because two tests
     * ({@code invalidMicroserviceUri_shouldReturn503} and {@code filterOrder_shouldReturnZero})
     * do not call {@code filterChain.filter()} — without lenient, MockitoExtension's strict
     * stubbing would throw {@code UnnecessaryStubbingException}.</p>
     */
    @BeforeEach
    void setUp() {
        routingFlagFilter = new RoutingFlagFilter(routingFlagService);
        lenient().when(filterChain.filter(any(ServerWebExchange.class)))
                .thenReturn(Mono.empty());
    }

    // =========================================================================
    // 4a. Flag Value "monolith" Tests — Request Passes Through to Monolith
    // =========================================================================

    /**
     * Verifies that when the routing flag for the Account Service is "monolith",
     * the request passes through to the default route URI (monolith) without
     * overriding {@code GATEWAY_REQUEST_URL_ATTR}.
     *
     * <p>This is the default behavior: all traffic goes to the monolith until the
     * operations team switches the flag to "microservice" via Redis.</p>
     */
    @Test
    void flagMonolith_shouldPassThroughToDefaultRoute() {
        // given
        Route route = buildFlagControlledRoute("account-actions",
                ACCOUNT_FLAG_KEY, ACCOUNT_SERVICE_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Account.action", route);
        when(routingFlagService.getFlag(ACCOUNT_FLAG_KEY))
                .thenReturn(Mono.just("monolith"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)).isNull();
    }

    /**
     * Verifies monolith pass-through for the Catalog Service route. This confirms
     * each service's routing flag is independent — Catalog can be on monolith while
     * Account is on microservice, or vice versa.
     */
    @Test
    void flagMonolith_catalogRoute_shouldPassThrough() {
        // given
        Route route = buildFlagControlledRoute("catalog-actions",
                CATALOG_FLAG_KEY, CATALOG_SERVICE_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Catalog.action", route);
        when(routingFlagService.getFlag(CATALOG_FLAG_KEY))
                .thenReturn(Mono.just("monolith"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)).isNull();
    }

    // =========================================================================
    // 4b. Flag Value "microservice" Tests — Route Overridden to Microservice
    // =========================================================================

    /**
     * Verifies that when the routing flag is "microservice", the filter overrides
     * {@code GATEWAY_REQUEST_URL_ATTR} to redirect the request to the Account Service.
     *
     * <p>This is the Strangler Fig pattern's core behavior: the API Gateway switches
     * traffic from the monolith to the new microservice at runtime.</p>
     */
    @Test
    void flagMicroservice_shouldOverrideGatewayRequestUrl() {
        // given
        Route route = buildFlagControlledRoute("account-actions",
                ACCOUNT_FLAG_KEY, ACCOUNT_SERVICE_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Account.action", route);
        when(routingFlagService.getFlag(ACCOUNT_FLAG_KEY))
                .thenReturn(Mono.just("microservice"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));

        Object requestUrlAttr = exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(requestUrlAttr).isNotNull();

        URI resolvedUri = (URI) requestUrlAttr;
        assertThat(resolvedUri.getScheme()).isEqualTo("http");
        assertThat(resolvedUri.getHost()).isEqualTo("account-service");
        assertThat(resolvedUri.getPort()).isEqualTo(8081);
        assertThat(resolvedUri.getPath()).isEqualTo("/actions/Account.action");
    }

    /**
     * Verifies that Catalog Service routing flag correctly redirects to the
     * Catalog microservice (port 8082).
     */
    @Test
    void flagMicroservice_catalogRoute_shouldRouteToMicroservice() {
        // given
        Route route = buildFlagControlledRoute("catalog-actions",
                CATALOG_FLAG_KEY, CATALOG_SERVICE_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Catalog.action", route);
        when(routingFlagService.getFlag(CATALOG_FLAG_KEY))
                .thenReturn(Mono.just("microservice"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));

        URI resolvedUri = (URI) exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(resolvedUri).isNotNull();
        assertThat(resolvedUri.getScheme()).isEqualTo("http");
        assertThat(resolvedUri.getHost()).isEqualTo("catalog-service");
        assertThat(resolvedUri.getPort()).isEqualTo(8082);
        assertThat(resolvedUri.getPath()).isEqualTo("/actions/Catalog.action");
    }

    /**
     * Verifies that Order Service routing flag correctly redirects to the
     * Order microservice (port 8083).
     */
    @Test
    void flagMicroservice_orderRoute_shouldRouteToMicroservice() {
        // given
        Route route = buildFlagControlledRoute("order-actions",
                ORDER_FLAG_KEY, ORDER_SERVICE_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Order.action", route);
        when(routingFlagService.getFlag(ORDER_FLAG_KEY))
                .thenReturn(Mono.just("microservice"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));

        URI resolvedUri = (URI) exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(resolvedUri).isNotNull();
        assertThat(resolvedUri.getScheme()).isEqualTo("http");
        assertThat(resolvedUri.getHost()).isEqualTo("order-service");
        assertThat(resolvedUri.getPort()).isEqualTo(8083);
        assertThat(resolvedUri.getPath()).isEqualTo("/actions/Order.action");
    }

    /**
     * Verifies that Cart routes use the Order Service routing flag and redirect to
     * the Order microservice. Per AAP Section 0.1.1, Cart is part of the Order bounded
     * context — both {@code /actions/Cart.action*} and {@code /actions/Order.action*}
     * use {@code routing.flag.order-service} and target {@code http://order-service:8083}.
     */
    @Test
    void flagMicroservice_cartRoute_shouldRouteToOrderService() {
        // given — Cart uses ORDER_FLAG_KEY and ORDER_SERVICE_URI
        Route route = buildFlagControlledRoute("cart-actions",
                ORDER_FLAG_KEY, ORDER_SERVICE_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Cart.action", route);
        when(routingFlagService.getFlag(ORDER_FLAG_KEY))
                .thenReturn(Mono.just("microservice"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));

        URI resolvedUri = (URI) exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(resolvedUri).isNotNull();
        assertThat(resolvedUri.getHost()).isEqualTo("order-service");
        assertThat(resolvedUri.getPort()).isEqualTo(8083);
        assertThat(resolvedUri.getPath()).isEqualTo("/actions/Cart.action");
    }

    // =========================================================================
    // 4c. Routes Without Routing Flag Metadata — Pass Through Unchanged
    // =========================================================================

    /**
     * Verifies that routes without routing flag metadata (e.g., static asset routes,
     * direct API routes) pass through unchanged without any Redis flag lookup.
     *
     * <p>Per the routing table: {@code /css/**}, {@code /images/**}, and
     * {@code /api/**} routes do NOT carry routing flag metadata. They always go to
     * their configured target without flag evaluation.</p>
     */
    @Test
    void routeWithoutMetadata_shouldPassThroughUnchanged() {
        // given — static route without routing-flag-key or microservice-uri metadata
        Route route = buildStaticRoute("static-assets", MONOLITH_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/css/jpetstore.css", route);

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)).isNull();
        // RoutingFlagService should NEVER be called for routes without metadata
        verify(routingFlagService, never()).getFlag(anyString());
    }

    /**
     * Verifies that when no {@code GATEWAY_ROUTE_ATTR} is set on the exchange
     * (e.g., no route matched), the filter passes through without error.
     *
     * <p>This should not happen in normal operation, but the filter handles it
     * gracefully by delegating to the filter chain.</p>
     */
    @Test
    void routeWithoutRouteAttribute_shouldPassThrough() {
        // given — exchange without GATEWAY_ROUTE_ATTR
        MockServerHttpRequest request = MockServerHttpRequest.get("/unknown-path").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        // Note: NOT setting GATEWAY_ROUTE_ATTR

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));
        verify(routingFlagService, never()).getFlag(anyString());
    }

    // =========================================================================
    // 4d. Error Handling Tests — Graceful Degradation
    // =========================================================================

    /**
     * Verifies that when Redis is unavailable (flag lookup returns error), the filter
     * falls back to the monolith route — request passes through without overriding
     * {@code GATEWAY_REQUEST_URL_ATTR}.
     *
     * <p><b>CRITICAL</b>: Per AAP Section 0.8.2, "the original monolith WAR must remain
     * deployable and fully functional" — Redis failures must NEVER break routing. This
     * test validates the graceful degradation behavior that ensures the monolith remains
     * the safe fallback when the routing flag infrastructure is unavailable.</p>
     */
    @Test
    void redisError_shouldFallbackToMonolith() {
        // given
        Route route = buildFlagControlledRoute("account-actions",
                ACCOUNT_FLAG_KEY, ACCOUNT_SERVICE_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Account.action", route);
        when(routingFlagService.getFlag(ACCOUNT_FLAG_KEY))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then — safe fallback to monolith, chain continues
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)).isNull();
    }

    /**
     * Verifies that an empty string flag value falls back to the monolith. An empty
     * string is NOT "microservice", so the filter should pass through without
     * overriding the route.
     *
     * <p>This handles edge cases where a Redis key exists but has an empty value
     * (e.g., after a misconfigured {@code SET routing.flag.account-service ""}).</p>
     */
    @Test
    void emptyFlagValue_shouldFallbackToMonolith() {
        // given
        Route route = buildFlagControlledRoute("account-actions",
                ACCOUNT_FLAG_KEY, ACCOUNT_SERVICE_URI);
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Account.action", route);
        when(routingFlagService.getFlag(ACCOUNT_FLAG_KEY))
                .thenReturn(Mono.just(""));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then — empty string ≠ "microservice", so pass through to monolith
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));
        assertThat(exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR)).isNull();
    }

    // =========================================================================
    // 4e. URI Preservation Tests — Path and Query Must Be Preserved
    // =========================================================================

    /**
     * Verifies that when routing to a microservice, the original request path and
     * query parameters are fully preserved in the resolved downstream URI.
     *
     * <p>Path preservation is critical: when switching
     * {@code /actions/Account.action?editAccountForm=&username=testuser} from the
     * monolith to the Account Service, the microservice must receive the exact same
     * path and query string — only the target host/port changes.</p>
     */
    @Test
    void microserviceRouting_shouldPreserveOriginalPath() {
        // given
        Route route = buildFlagControlledRoute("account-actions",
                ACCOUNT_FLAG_KEY, ACCOUNT_SERVICE_URI);
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/actions/Account.action")
                .queryParam("editAccountForm", "")
                .queryParam("username", "testuser")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        when(routingFlagService.getFlag(ACCOUNT_FLAG_KEY))
                .thenReturn(Mono.just("microservice"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));

        URI resolvedUri = (URI) exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(resolvedUri).isNotNull();
        // Scheme, host, and port come from the microservice URI
        assertThat(resolvedUri.getScheme()).isEqualTo("http");
        assertThat(resolvedUri.getHost()).isEqualTo("account-service");
        assertThat(resolvedUri.getPort()).isEqualTo(8081);
        // Path and query come from the original request — must be preserved exactly
        assertThat(resolvedUri.getPath()).isEqualTo("/actions/Account.action");
        assertThat(resolvedUri.getQuery()).contains("username=testuser");
    }

    /**
     * Verifies path and query preservation for the Catalog Service with a different
     * set of query parameters — category browsing is a core workflow.
     *
     * <p>URL: {@code /actions/Catalog.action?viewCategory=&categoryId=FISH}</p>
     */
    @Test
    void microserviceRouting_shouldPreservePathAndQuery() {
        // given
        Route route = buildFlagControlledRoute("catalog-actions",
                CATALOG_FLAG_KEY, CATALOG_SERVICE_URI);
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/actions/Catalog.action")
                .queryParam("viewCategory", "")
                .queryParam("categoryId", "FISH")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        when(routingFlagService.getFlag(CATALOG_FLAG_KEY))
                .thenReturn(Mono.just("microservice"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then
        StepVerifier.create(result).verifyComplete();
        verify(filterChain).filter(any(ServerWebExchange.class));

        URI resolvedUri = (URI) exchange.getAttributes()
                .get(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        assertThat(resolvedUri).isNotNull();
        assertThat(resolvedUri.getScheme()).isEqualTo("http");
        assertThat(resolvedUri.getHost()).isEqualTo("catalog-service");
        assertThat(resolvedUri.getPort()).isEqualTo(8082);
        assertThat(resolvedUri.getPath()).isEqualTo("/actions/Catalog.action");
        assertThat(resolvedUri.getQuery()).contains("categoryId=FISH");
    }

    // =========================================================================
    // 4f. 503 Service Unavailable Test — Invalid Microservice URI
    // =========================================================================

    /**
     * Verifies that an invalid {@code microservice-uri} metadata value results in a
     * 503 Service Unavailable response without continuing the filter chain.
     *
     * <p>This is a configuration error scenario: the {@code RouteConfig} has a malformed
     * microservice URI value. The filter catches the {@code IllegalArgumentException}
     * from {@code URI.create()} and returns 503 instead of propagating the error.</p>
     */
    @Test
    void invalidMicroserviceUri_shouldReturn503() {
        // given — route with invalid microservice URI metadata
        Route route = buildFlagControlledRoute("broken-route",
                ACCOUNT_FLAG_KEY, "://invalid-uri");
        MockServerWebExchange exchange = buildExchangeWithRoute(
                "/actions/Account.action", route);
        when(routingFlagService.getFlag(ACCOUNT_FLAG_KEY))
                .thenReturn(Mono.just("microservice"));

        // when
        Mono<Void> result = routingFlagFilter.filter(exchange, filterChain);

        // then — 503 response, filter chain NOT called
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(filterChain, never()).filter(any(ServerWebExchange.class));
    }

    // =========================================================================
    // 4g. Filter Order Test
    // =========================================================================

    /**
     * Verifies that the filter's execution order is 0, ensuring it runs:
     * <ul>
     *   <li><b>AFTER</b> AuthenticationFilter (order -100) — JWT validation first</li>
     *   <li><b>BEFORE</b> RouteToRequestUrlFilter (order 10000) — route override
     *       is applied before downstream URL construction</li>
     * </ul>
     */
    @Test
    void filterOrder_shouldReturnZero() {
        // when
        int order = routingFlagFilter.getOrder();

        // then
        assertThat(order).isEqualTo(0);
    }

    // =========================================================================
    // Helper Methods — Route and Exchange Construction
    // =========================================================================

    /**
     * Builds a {@link Route} with routing flag metadata, simulating a flag-controlled
     * route as configured in {@code RouteConfig}.
     *
     * <p>The route's default URI is the monolith. When the routing flag is "microservice",
     * the filter overrides this to the microservice URI.</p>
     *
     * @param routeId        the route identifier (e.g., "account-actions")
     * @param flagKey        the Redis routing flag key (e.g., "routing.flag.account-service")
     * @param microserviceUri the microservice target URI (e.g., "http://account-service:8081")
     * @return a Route with routing flag metadata
     */
    private Route buildFlagControlledRoute(String routeId, String flagKey,
                                           String microserviceUri) {
        return Route.async()
                .id(routeId)
                .uri(URI.create(MONOLITH_URI))
                .order(0)
                .asyncPredicate(swe -> Mono.just(true))
                .metadata("routing-flag-key", flagKey)
                .metadata("microservice-uri", microserviceUri)
                .build();
    }

    /**
     * Builds a {@link Route} WITHOUT routing flag metadata, simulating a static route
     * (e.g., for static assets, direct API routes, or the monolith catch-all).
     *
     * @param routeId   the route identifier (e.g., "static-assets")
     * @param targetUri the target URI string (e.g., "http://monolith:8080")
     * @return a Route without routing flag metadata
     */
    private Route buildStaticRoute(String routeId, String targetUri) {
        return Route.async()
                .id(routeId)
                .uri(URI.create(targetUri))
                .order(0)
                .asyncPredicate(swe -> Mono.just(true))
                .build();
    }

    /**
     * Builds a {@link MockServerWebExchange} with a matched route set in the
     * exchange attributes, simulating Spring Cloud Gateway's route resolution.
     *
     * @param path  the HTTP request path (e.g., "/actions/Account.action")
     * @param route the resolved route to set as {@code GATEWAY_ROUTE_ATTR}
     * @return a mock exchange ready for filter testing
     */
    private MockServerWebExchange buildExchangeWithRoute(String path, Route route) {
        MockServerHttpRequest request = MockServerHttpRequest.get(path).build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }
}
