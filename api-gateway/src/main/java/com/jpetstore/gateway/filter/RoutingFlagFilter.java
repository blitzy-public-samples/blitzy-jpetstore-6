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

import com.jpetstore.gateway.config.RoutingFlagConfig;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway {@link GlobalFilter} implementing the Strangler Fig pattern's
 * runtime routing mechanism for the JPetStore monolith-to-microservices decomposition.
 *
 * <p>This filter is the <b>core</b> of the traffic switching strategy described in
 * AAP Section 0.7.6. It evaluates per-service routing flags stored in Redis at request
 * time and dynamically redirects traffic from the monolith to the corresponding
 * microservice when a flag is set to {@code "microservice"}.</p>
 *
 * <h3>How It Works</h3>
 * <ol>
 *   <li>Spring Cloud Gateway's {@code RoutePredicateHandlerMapping} resolves the matching
 *       route and stores it in the exchange attributes as {@code GATEWAY_ROUTE_ATTR}.</li>
 *   <li>This filter reads the resolved route's metadata for two keys:
 *       {@code routing-flag-key} (the Redis key name) and {@code microservice-uri}
 *       (the target microservice URL).</li>
 *   <li>If both metadata keys are present (i.e., this is a flag-controlled route),
 *       the filter calls {@link RoutingFlagConfig.RoutingFlagService#getFlag(String)}
 *       to reactively read the flag value from Redis (with local cache, 1s TTL).</li>
 *   <li>If the flag value is {@code "microservice"}, the filter:
 *       <ul>
 *         <li>Creates a new {@link Route} with the microservice URI and replaces the
 *             original route in the exchange attributes ({@code GATEWAY_ROUTE_ATTR})</li>
 *         <li>Sets the resolved downstream URL in {@code GATEWAY_REQUEST_URL_ATTR},
 *             preserving the original request path and query parameters</li>
 *       </ul>
 *   </li>
 *   <li>If the flag value is {@code "monolith"} (default), the filter passes through
 *       without modification — traffic continues to the monolith as configured.</li>
 * </ol>
 *
 * <h3>Flag-Controlled Routes</h3>
 * <p>Only routes with both metadata keys participate in flag evaluation. Routes defined
 * in {@link com.jpetstore.gateway.config.RouteConfig} carry this metadata on the four
 * Stripes ActionBean routes:</p>
 * <table>
 *   <tr><th>Route ID</th><th>Flag Key</th><th>Microservice URI</th></tr>
 *   <tr><td>account-actions</td><td>routing.flag.account-service</td>
 *       <td>http://account-service:8081</td></tr>
 *   <tr><td>catalog-actions</td><td>routing.flag.catalog-service</td>
 *       <td>http://catalog-service:8082</td></tr>
 *   <tr><td>cart-actions</td><td>routing.flag.order-service</td>
 *       <td>http://order-service:8083</td></tr>
 *   <tr><td>order-actions</td><td>routing.flag.order-service</td>
 *       <td>http://order-service:8083</td></tr>
 * </table>
 *
 * <h3>Filter Order</h3>
 * <p>Order = 0, which runs:</p>
 * <ul>
 *   <li><b>AFTER</b> {@code AuthenticationFilter} (order -100), so JWT validation
 *       occurs before routing decisions</li>
 *   <li><b>AFTER</b> route resolution ({@code RoutePredicateHandlerMapping}), so
 *       {@code GATEWAY_ROUTE_ATTR} is available</li>
 *   <li><b>BEFORE</b> {@code RouteToRequestUrlFilter} (order 10000), so the
 *       replaced route URI is used to construct the downstream request URL</li>
 * </ul>
 *
 * <h3>Graceful Degradation</h3>
 * <p>If Redis is unavailable, {@link RoutingFlagConfig.RoutingFlagService#getFlag(String)}
 * returns {@code "monolith"} (the safe default). Additionally, any error in this filter's
 * reactive chain triggers {@code onErrorResume} which passes through to the monolith.
 * Redis failures and URI construction errors never break routing.</p>
 *
 * <h3>Reactive Execution</h3>
 * <p>Fully non-blocking. The Redis flag lookup is reactive (via {@code Mono<String>}).
 * No blocking calls. No servlet APIs. Runs on Spring Cloud Gateway's Netty event loop.</p>
 *
 * @see RoutingFlagConfig.RoutingFlagService
 * @see com.jpetstore.gateway.config.RouteConfig
 * @see org.springframework.cloud.gateway.filter.GlobalFilter
 */
@Component
public class RoutingFlagFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RoutingFlagFilter.class);

    // -------------------------------------------------------------------------
    // Route Metadata Key Constants
    // -------------------------------------------------------------------------

    /**
     * Metadata key on flag-controlled routes that holds the Redis key name
     * for the routing flag. Value example: {@code "routing.flag.catalog-service"}.
     *
     * @see com.jpetstore.gateway.config.RouteConfig
     */
    static final String ROUTING_FLAG_KEY_METADATA = "routing-flag-key";

    /**
     * Metadata key on flag-controlled routes that holds the target microservice
     * URI to use when the routing flag is set to {@code "microservice"}.
     * Value example: {@code "http://catalog-service:8082"}.
     *
     * @see com.jpetstore.gateway.config.RouteConfig
     */
    static final String MICROSERVICE_URI_METADATA = "microservice-uri";

    /**
     * The routing flag value that triggers switching to the microservice target.
     * When the Redis flag value equals this string (case-insensitive), the route
     * URI is replaced with the microservice target.
     */
    private static final String FLAG_VALUE_MICROSERVICE = "microservice";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    /**
     * Service providing cached, reactive access to per-service routing flags in Redis.
     * Constructor-injected — see {@link RoutingFlagConfig.RoutingFlagService}.
     */
    private final RoutingFlagConfig.RoutingFlagService routingFlagService;

    /**
     * Constructs the RoutingFlagFilter with the required routing flag service.
     *
     * <p>The {@link RoutingFlagConfig.RoutingFlagService} is created by
     * {@link RoutingFlagConfig} and provides cached, reactive access to per-service
     * routing flags stored in Redis with a 1-second local cache TTL.</p>
     *
     * @param routingFlagService the service for reading routing flags from Redis;
     *                           must not be {@code null}
     */
    public RoutingFlagFilter(RoutingFlagConfig.RoutingFlagService routingFlagService) {
        this.routingFlagService = routingFlagService;
        log.info("RoutingFlagFilter initialized — Strangler Fig routing is active");
    }

    // -------------------------------------------------------------------------
    // Ordered Interface Implementation
    // -------------------------------------------------------------------------

    /**
     * Returns the filter execution order.
     *
     * <p>Order = 0 ensures this filter runs:</p>
     * <ul>
     *   <li><b>AFTER</b> {@code AuthenticationFilter} (order -100): JWT authentication
     *       is validated before any routing decisions are made</li>
     *   <li><b>AFTER</b> route resolution: the {@code GATEWAY_ROUTE_ATTR} is already
     *       populated by {@code RoutePredicateHandlerMapping}</li>
     *   <li><b>BEFORE</b> {@code RouteToRequestUrlFilter} (order 10000): the replaced
     *       route URI is picked up when constructing the downstream request URL</li>
     * </ul>
     *
     * @return 0 — runs after auth filters but before URL resolution
     */
    @Override
    public int getOrder() {
        return 0;
    }

    // -------------------------------------------------------------------------
    // GlobalFilter Implementation — Core Routing Logic
    // -------------------------------------------------------------------------

    /**
     * Evaluates the routing flag for the current request's matched route and, if the
     * flag indicates microservice routing, replaces the route URI and sets the resolved
     * downstream URL before the request is proxied.
     *
     * <p>Execution flow:</p>
     * <ol>
     *   <li>Retrieve the resolved route from exchange attributes</li>
     *   <li>If no route or no routing flag metadata → pass through (not flag-controlled)</li>
     *   <li>Read the routing flag from Redis (cached, non-blocking)</li>
     *   <li>If flag = "microservice" → replace route with microservice URI in exchange
     *       and set GATEWAY_REQUEST_URL_ATTR with the resolved downstream URL preserving
     *       the original request path and query parameters</li>
     *   <li>If flag = "monolith" → pass through with original route (monolith URI)</li>
     *   <li>On any error → pass through (safe fallback to monolith)</li>
     * </ol>
     *
     * @param exchange the current server web exchange containing the resolved route
     * @param chain    the Gateway filter chain for continuing downstream
     * @return {@code Mono<Void>} — fully reactive, non-blocking
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Step 1: Retrieve the resolved route from exchange attributes.
        // This was set by RoutePredicateHandlerMapping before any GlobalFilter runs.
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            // No route resolved — should not happen in normal operation, but handle gracefully.
            log.trace("No route attribute found in exchange — passing through");
            return chain.filter(exchange);
        }

        // Step 2: Check if this is a flag-controlled route by looking for routing metadata.
        // Only the 4 Stripes ActionBean routes carry this metadata (see RouteConfig).
        // Static API routes, asset routes, and the catch-all do NOT have metadata.
        Map<String, Object> metadata = route.getMetadata();
        String flagKey = getMetadataString(metadata, ROUTING_FLAG_KEY_METADATA);
        String microserviceUri = getMetadataString(metadata, MICROSERVICE_URI_METADATA);

        if (flagKey == null || microserviceUri == null) {
            // Not a flag-controlled route — pass through without Redis lookup.
            // This is the common case for /api/**, /css/**, /images/**, and /**.
            return chain.filter(exchange);
        }

        // Step 3: This IS a flag-controlled route — read the routing flag from Redis.
        // The RoutingFlagService:
        //   - Returns "monolith" or "microservice"
        //   - Uses a 1-second local cache to minimize Redis overhead
        //   - Defaults to "monolith" on Redis errors (graceful degradation)
        return routingFlagService.getFlag(flagKey)
                .flatMap(flagValue -> {
                    if (FLAG_VALUE_MICROSERVICE.equalsIgnoreCase(flagValue)) {
                        // Flag says "microservice" — switch the route to the microservice URI.
                        log.debug("Routing flag [{}] = '{}' — switching route '{}' to {}",
                                flagKey, flagValue, route.getId(), microserviceUri);

                        return routeToMicroservice(exchange, chain, route, microserviceUri);
                    }
                    // Flag says "monolith" (or any other unrecognized value) — keep original route.
                    log.trace("Routing flag [{}] = '{}' — keeping route '{}' on monolith",
                            flagKey, flagValue, route.getId());
                    return chain.filter(exchange);
                })
                .onErrorResume(error -> {
                    // On ANY error in the reactive chain (Redis failure, URI construction error,
                    // unexpected exception), default to the monolith — safe fallback.
                    // Per AAP Section 0.8.2: "the original monolith WAR must remain deployable
                    // and fully functional" — Redis failures must never break routing.
                    log.warn("Error evaluating routing flag [{}] for route '{}' — "
                            + "falling back to monolith: {}", flagKey, route.getId(),
                            error.getMessage());
                    return chain.filter(exchange);
                });
    }

    // -------------------------------------------------------------------------
    // Private Helper Methods
    // -------------------------------------------------------------------------

    /**
     * Redirects the current request to the microservice by replacing the route in the
     * exchange attributes and setting the resolved downstream URL.
     *
     * <p>This method performs two complementary operations:</p>
     * <ol>
     *   <li>Replaces {@code GATEWAY_ROUTE_ATTR} with a new {@link Route} that has the
     *       microservice URI — this ensures {@code RouteToRequestUrlFilter} (order 10000)
     *       constructs the downstream URL from the microservice URI</li>
     *   <li>Sets {@code GATEWAY_REQUEST_URL_ATTR} with the fully resolved downstream URL
     *       preserving the original request path and query parameters — the scheme, host,
     *       and port come from the microservice URI while the path and query come from
     *       the original request</li>
     * </ol>
     *
     * <p>Path preservation is critical: when switching
     * {@code /actions/Catalog.action?viewCategory=&categoryId=FISH} from the monolith to
     * catalog-service, the microservice must receive the same path and query string.</p>
     *
     * @param exchange        the current server web exchange
     * @param chain           the filter chain to continue processing
     * @param originalRoute   the original matched route (will be replaced)
     * @param microserviceUri the microservice target URI string (e.g., "http://catalog-service:8082")
     * @return {@code Mono<Void>} continuing the filter chain, or 503 on URI errors
     */
    private Mono<Void> routeToMicroservice(ServerWebExchange exchange,
                                           GatewayFilterChain chain,
                                           Route originalRoute,
                                           String microserviceUri) {
        try {
            // Parse the microservice base URI (scheme + host + port)
            URI targetUri = URI.create(microserviceUri);

            // Validate the parsed URI has the required components
            if (targetUri.getScheme() == null || targetUri.getHost() == null) {
                log.error("Invalid microservice URI '{}' — missing scheme or host", microserviceUri);
                return onServiceUnavailable(exchange);
            }

            // Build the resolved downstream URL preserving the original request path and query.
            // The scheme, host, and port come from the microservice URI.
            // The path and query come from the original request URI.
            URI originalRequestUri = exchange.getRequest().getURI();
            URI resolvedUri = buildResolvedUri(targetUri, originalRequestUri);

            // Operation 1: Replace the route in exchange attributes with a new route
            // pointing to the microservice URI. This ensures RouteToRequestUrlFilter
            // (order 10000) uses the microservice URI when constructing the final URL.
            Route microserviceRoute = buildRouteWithNewUri(originalRoute, targetUri);
            exchange.getAttributes().put(
                    ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                    microserviceRoute
            );

            // Operation 2: Set the resolved downstream URL directly in the exchange.
            // This preserves the original request path and query parameters while
            // redirecting to the microservice's scheme/host/port.
            exchange.getAttributes().put(
                    ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR,
                    resolvedUri
            );

            log.debug("Route '{}' redirected to microservice — resolved URI: {}",
                    originalRoute.getId(), resolvedUri);

            return chain.filter(exchange);
        } catch (IllegalArgumentException | URISyntaxException e) {
            // Invalid microservice URI or URI construction failure — return 503.
            // This is a configuration error (bad microservice-uri metadata value).
            log.error("Failed to construct microservice URI from '{}' for route '{}': {}",
                    microserviceUri, originalRoute.getId(), e.getMessage());
            return onServiceUnavailable(exchange);
        }
    }

    /**
     * Constructs the fully resolved downstream URI by combining the microservice's
     * scheme, host, and port with the original request's path and query parameters.
     *
     * <p>This ensures path preservation: when the API Gateway receives a request like
     * {@code /actions/Catalog.action?viewCategory=&categoryId=FISH}, the microservice
     * receives the exact same path and query — only the target host changes.</p>
     *
     * @param targetUri          the parsed microservice base URI (scheme + host + port)
     * @param originalRequestUri the original incoming request URI (path + query)
     * @return the fully resolved downstream URI
     * @throws URISyntaxException if the resolved URI has invalid syntax
     */
    private static URI buildResolvedUri(URI targetUri, URI originalRequestUri)
            throws URISyntaxException {
        return new URI(
                targetUri.getScheme(),
                null,  // userInfo — not used for service-to-service calls
                targetUri.getHost(),
                targetUri.getPort(),
                originalRequestUri.getRawPath(),
                originalRequestUri.getRawQuery(),
                null   // fragment — not forwarded in proxy requests
        );
    }

    /**
     * Creates a new {@link Route} that is identical to the original except for the URI,
     * which is replaced with the microservice target URI.
     *
     * <p>All other route properties are preserved:</p>
     * <ul>
     *   <li>Route ID — same as original (for logging and debugging)</li>
     *   <li>Order — same as original</li>
     *   <li>Predicate — same as original (path matching still works correctly)</li>
     *   <li>Filters — same as original (any route-level filters are preserved)</li>
     *   <li>Metadata — same as original (flag keys preserved for debugging)</li>
     * </ul>
     *
     * @param originalRoute the original Route resolved by RoutePredicateHandlerMapping
     * @param newUri        the parsed microservice target URI
     * @return a new Route with the microservice URI
     */
    private static Route buildRouteWithNewUri(Route originalRoute, URI newUri) {
        // Build a new route preserving all original properties except the URI.
        // Route.async() creates an AsyncBuilder for reactive predicate routes.
        Route.AsyncBuilder builder = Route.async()
                .id(originalRoute.getId())
                .uri(newUri)
                .order(originalRoute.getOrder())
                .asyncPredicate(originalRoute.getPredicate())
                .metadata(originalRoute.getMetadata());

        // Copy all route-level gateway filters from the original route.
        // Route.getFilters() returns the list of GatewayFilter instances
        // configured for this specific route (via RouteLocator).
        originalRoute.getFilters().forEach(builder::filter);

        return builder.build();
    }

    /**
     * Extracts a {@code String} metadata value from a route's metadata map.
     *
     * @param metadata the route metadata map (never null for resolved routes)
     * @param key      the metadata key to look up
     * @return the String value, or {@code null} if the key is absent or the value is
     *         not a {@code String}
     */
    private static String getMetadataString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return (value instanceof String) ? (String) value : null;
    }

    /**
     * Returns an HTTP 503 Service Unavailable response when the microservice target
     * URI cannot be constructed or is invalid.
     *
     * <p>Per the AAP requirements: "If a target microservice is unreachable, the gateway
     * returns a 503 Service Unavailable response." This covers cases where the
     * {@code microservice-uri} metadata value is malformed or missing required components
     * (scheme, host).</p>
     *
     * @param exchange the current server web exchange
     * @return {@code Mono<Void>} that completes the response with 503 status
     */
    private Mono<Void> onServiceUnavailable(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        log.warn("Returning 503 Service Unavailable for request: {}",
                exchange.getRequest().getURI());
        return response.setComplete();
    }
}
