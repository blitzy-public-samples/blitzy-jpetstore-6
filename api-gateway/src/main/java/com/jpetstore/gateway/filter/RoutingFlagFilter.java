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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Spring Cloud Gateway GlobalFilter implementing the Strangler Fig runtime routing mechanism.
 *
 * <p>This filter is the <b>core</b> of the monolith-to-microservices traffic switching
 * strategy described in AAP Section 0.7.6. It evaluates per-service routing flags stored
 * in Redis at request time and dynamically redirects traffic from the monolith to the
 * corresponding microservice when a flag is set to {@code "microservice"}.</p>
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
 *   <li>If the flag value is {@code "microservice"}, the filter creates a new {@link Route}
 *       with the microservice URI and replaces the original route in the exchange attributes.
 *       The downstream {@code RouteToRequestUrlFilter} (order 10000) then uses the new URI.</li>
 *   <li>If the flag value is {@code "monolith"} (default), the filter passes through without
 *       modification — traffic continues to the monolith as configured in the route.</li>
 * </ol>
 *
 * <h3>Flag-Controlled Routes</h3>
 * <p>Only routes with both metadata keys participate in flag evaluation. Routes defined
 * in {@link com.jpetstore.gateway.config.RouteConfig} carry this metadata on the four
 * Stripes ActionBean routes:</p>
 * <table>
 *   <tr><th>Route ID</th><th>Flag Key</th><th>Microservice URI</th></tr>
 *   <tr><td>account-actions</td><td>routing.flag.account-service</td><td>http://account-service:8081</td></tr>
 *   <tr><td>catalog-actions</td><td>routing.flag.catalog-service</td><td>http://catalog-service:8082</td></tr>
 *   <tr><td>cart-actions</td><td>routing.flag.order-service</td><td>http://order-service:8083</td></tr>
 *   <tr><td>order-actions</td><td>routing.flag.order-service</td><td>http://order-service:8083</td></tr>
 * </table>
 *
 * <h3>GlobalFilter vs WebFilter</h3>
 * <p>This filter is a {@link GlobalFilter} (NOT a {@code WebFilter}) because it needs
 * access to Gateway-specific exchange attributes ({@code GATEWAY_ROUTE_ATTR}) that are
 * only populated within the Gateway filter chain. The {@link AuthenticationFilter},
 * by contrast, is a {@code WebFilter} because it needs to populate the
 * {@code ReactiveSecurityContext} visible to Spring Security's authorization check.</p>
 *
 * <h3>Filter Order</h3>
 * <p>Order = 0, which runs:
 * <ul>
 *   <li><b>AFTER</b> route resolution ({@code RoutePredicateHandlerMapping}), so
 *       {@code GATEWAY_ROUTE_ATTR} is available</li>
 *   <li><b>BEFORE</b> {@code RouteToRequestUrlFilter} (order 10000), so the
 *       replaced route URI is used to construct the downstream request URL</li>
 * </ul>
 *
 * <h3>Graceful Degradation</h3>
 * <p>If Redis is unavailable, {@link RoutingFlagConfig.RoutingFlagService#getFlag(String)}
 * returns {@code "monolith"} (the safe default), so all traffic stays on the monolith.
 * Redis failures never break routing.</p>
 *
 * <h3>Reactive Execution</h3>
 * <p>Fully non-blocking. The Redis flag lookup is reactive (via {@code Mono<String>}).
 * No blocking calls. Runs on Spring Cloud Gateway's Netty event loop.</p>
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
     * When the Redis flag value equals this string, the route URI is replaced.
     */
    private static final String FLAG_VALUE_MICROSERVICE = "microservice";

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    /** Service providing cached, reactive access to per-service routing flags in Redis. */
    private final RoutingFlagConfig.RoutingFlagService routingFlagService;

    /**
     * Constructs the RoutingFlagFilter with the required routing flag service.
     *
     * @param routingFlagService the service for reading routing flags from Redis
     */
    public RoutingFlagFilter(RoutingFlagConfig.RoutingFlagService routingFlagService) {
        this.routingFlagService = routingFlagService;
        log.info("RoutingFlagFilter initialized — Strangler Fig routing is active");
    }

    // -------------------------------------------------------------------------
    // GlobalFilter Implementation
    // -------------------------------------------------------------------------

    /**
     * Returns the filter execution order.
     *
     * <p>Order = 0 ensures this filter runs:
     * <ul>
     *   <li><b>AFTER</b> route resolution: the {@code GATEWAY_ROUTE_ATTR} is already
     *       populated by {@code RoutePredicateHandlerMapping}</li>
     *   <li><b>BEFORE</b> {@code RouteToRequestUrlFilter} (order 10000): the replaced
     *       route URI is picked up when constructing the downstream request URL</li>
     * </ul>
     *
     * @return 0
     */
    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * Evaluates the routing flag for the current request's matched route and, if the
     * flag indicates microservice routing, replaces the route URI with the microservice
     * target before the downstream request is constructed.
     *
     * <p>Execution flow:</p>
     * <ol>
     *   <li>Retrieve the resolved route from exchange attributes</li>
     *   <li>If no route or no routing flag metadata → pass through (not a flag-controlled route)</li>
     *   <li>Read the routing flag from Redis (cached, non-blocking)</li>
     *   <li>If flag = "microservice" → replace route with microservice URI in exchange</li>
     *   <li>If flag = "monolith" → pass through with original route (monolith URI)</li>
     * </ol>
     *
     * @param exchange the current server web exchange containing the resolved route
     * @param chain    the Gateway filter chain for continuing downstream
     * @return {@code Mono<Void>} — fully reactive, non-blocking
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Retrieve the resolved route from exchange attributes. This was set by
        // RoutePredicateHandlerMapping before any GlobalFilter runs.
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            // No route resolved — should not happen in normal operation, but handle gracefully
            log.trace("No route attribute found in exchange — passing through");
            return chain.filter(exchange);
        }

        // Check if this is a flag-controlled route by looking for routing metadata.
        // Only the 4 Stripes ActionBean routes carry this metadata (see RouteConfig).
        // Static API routes, asset routes, and the catch-all do NOT have metadata.
        String flagKey = getMetadataString(route, ROUTING_FLAG_KEY_METADATA);
        String microserviceUri = getMetadataString(route, MICROSERVICE_URI_METADATA);

        if (flagKey == null || microserviceUri == null) {
            // Not a flag-controlled route — pass through without Redis lookup.
            // This is the common case for /api/**, /css/**, /images/**, and /**.
            return chain.filter(exchange);
        }

        // This IS a flag-controlled route — read the routing flag from Redis (cached).
        // The RoutingFlagService:
        //   - Returns "monolith" or "microservice"
        //   - Uses a 1-second local cache to minimize Redis overhead
        //   - Defaults to "monolith" on Redis errors (graceful degradation)
        return routingFlagService.getFlag(flagKey)
                .flatMap(flagValue -> {
                    if (FLAG_VALUE_MICROSERVICE.equalsIgnoreCase(flagValue)) {
                        // Flag says "microservice" — switch the route to the microservice URI.
                        // We rebuild the Route with a new URI so that the downstream
                        // RouteToRequestUrlFilter (order 10000) uses the microservice URL
                        // when constructing the final request URL.
                        log.debug("Routing flag [{}] = '{}' — switching route '{}' from monolith to {}",
                                flagKey, flagValue, route.getId(), microserviceUri);

                        Route microserviceRoute = buildRouteWithNewUri(route, microserviceUri);
                        exchange.getAttributes().put(
                                ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                                microserviceRoute
                        );
                    } else {
                        // Flag says "monolith" (or any other value) — keep original route.
                        log.trace("Routing flag [{}] = '{}' — keeping route '{}' on monolith",
                                flagKey, flagValue, route.getId());
                    }
                    return chain.filter(exchange);
                });
    }

    // -------------------------------------------------------------------------
    // Private Helper Methods
    // -------------------------------------------------------------------------

    /**
     * Extracts a String metadata value from a route's metadata map.
     *
     * @param route the resolved route
     * @param key   the metadata key to look up
     * @return the String value, or {@code null} if the key is absent or the value is not a String
     */
    private static String getMetadataString(Route route, String key) {
        Object value = route.getMetadata().get(key);
        return (value instanceof String) ? (String) value : null;
    }

    /**
     * Creates a new {@link Route} that is identical to the original except for the URI,
     * which is replaced with the microservice target URI.
     *
     * <p>All other route properties are preserved:
     * <ul>
     *   <li>Route ID — same as original (for logging and debugging)</li>
     *   <li>Order — same as original</li>
     *   <li>Predicate — same as original (path matching still works correctly)</li>
     *   <li>Filters — same as original (any route-level filters are preserved)</li>
     *   <li>Metadata — same as original (flag keys preserved for debugging)</li>
     * </ul>
     *
     * @param originalRoute  the original Route resolved by RoutePredicateHandlerMapping
     * @param newUriString   the microservice URI string (e.g., "http://catalog-service:8082")
     * @return a new Route with the microservice URI
     */
    private static Route buildRouteWithNewUri(Route originalRoute, String newUriString) {
        URI newUri = URI.create(newUriString);

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
}
