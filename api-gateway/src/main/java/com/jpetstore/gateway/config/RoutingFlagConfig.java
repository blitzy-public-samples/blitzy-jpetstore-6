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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed per-service routing flag configuration for the Strangler Fig pattern.
 *
 * <p>This is the <b>foundational</b> configuration component that enables runtime-switchable
 * routing between the monolith and individual microservices. It provides:
 * <ul>
 *   <li>A {@link ReactiveRedisTemplate} bean for non-blocking Redis string operations</li>
 *   <li>A {@link RoutingFlagService} bean that reads routing flag values from Redis,
 *       caches them locally with a configurable TTL (default 1 second), and exposes
 *       them to the {@code RoutingFlagFilter} for route decision-making</li>
 * </ul>
 *
 * <h2>Purpose</h2>
 * Allow operations teams to switch traffic from monolith to microservice (or back) for any
 * bounded context by changing a single Redis key value — no redeployment, no restart required.
 *
 * <h2>Redis Key Convention</h2>
 * Three routing flags exist, one per bounded context:
 * <ul>
 *   <li>{@code routing.flag.account-service} → Account bounded context</li>
 *   <li>{@code routing.flag.catalog-service} → Catalog bounded context</li>
 *   <li>{@code routing.flag.order-service} → Order bounded context (includes Cart)</li>
 * </ul>
 * Each key's value is either {@code "monolith"} (default) or {@code "microservice"}.
 *
 * <h2>Reactive Requirement</h2>
 * <b>CRITICAL</b>: Spring Cloud Gateway runs on Netty — all Redis operations use
 * {@link ReactiveRedisTemplate} (non-blocking). Blocking {@code RedisTemplate} would
 * deadlock the Netty event loop.
 *
 * <h2>Graceful Degradation</h2>
 * If Redis is unavailable, all flags default to {@code "monolith"}, ensuring the system
 * safely falls back to the monolith for all traffic. Redis failures never break routing.
 *
 * @see RoutingFlagService
 */
@Configuration
public class RoutingFlagConfig {

    /**
     * Local cache TTL in seconds for routing flag values.
     * Default: 1 second — balances Redis overhead reduction with near-real-time flag switching.
     * After changing a Redis flag, ALL new requests use the new target within this window.
     */
    @Value("${routing.flags.cache-ttl-seconds:1}")
    private int cacheTtlSeconds;

    /**
     * Redis key prefix for routing flags.
     * Default: "routing.flag." — keys follow the pattern "routing.flag.{service-name}".
     */
    @Value("${routing.flags.redis-key-prefix:routing.flag.}")
    private String redisKeyPrefix;

    /**
     * Default routing target for the Account Service bounded context.
     * Default: "monolith" — system starts in full-monolith mode.
     */
    @Value("${routing.flags.account-service:monolith}")
    private String accountServiceDefault;

    /**
     * Default routing target for the Catalog Service bounded context.
     * Default: "monolith" — system starts in full-monolith mode.
     */
    @Value("${routing.flags.catalog-service:monolith}")
    private String catalogServiceDefault;

    /**
     * Default routing target for the Order Service bounded context (includes Cart).
     * Default: "monolith" — system starts in full-monolith mode.
     */
    @Value("${routing.flags.order-service:monolith}")
    private String orderServiceDefault;

    /**
     * Creates a {@link ReactiveRedisTemplate} bean configured for string key-value operations.
     *
     * <p>Uses {@link StringRedisSerializer} for both keys and values since routing flags
     * are simple string pairs: key = "routing.flag.{service-name}", value = "monolith" or "microservice".
     *
     * <p>The {@link ReactiveRedisConnectionFactory} parameter is auto-configured by Spring Boot
     * from the {@code spring.data.redis.*} properties in {@code application.yml}.
     *
     * @param connectionFactory the reactive Redis connection factory, auto-configured by Spring Boot
     * @return a reactive Redis template for string key-value operations
     */
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory connectionFactory) {
        RedisSerializationContext<String, String> serializationContext =
                RedisSerializationContext.<String, String>newSerializationContext(new StringRedisSerializer())
                        .build();
        return new ReactiveRedisTemplate<>(connectionFactory, serializationContext);
    }

    /**
     * Creates a {@link RoutingFlagService} bean that provides cached, reactive access to
     * per-service routing flags stored in Redis.
     *
     * <p>The service is initialized with:
     * <ul>
     *   <li>The reactive Redis template for non-blocking flag reads</li>
     *   <li>The cache TTL (default 1 second) for local caching of flag values</li>
     *   <li>The Redis key prefix for constructing full flag keys</li>
     *   <li>Default flag values (all "monolith") for graceful degradation when Redis is unavailable</li>
     * </ul>
     *
     * @param redisTemplate the reactive Redis template for string operations
     * @return a routing flag service instance configured with defaults from application.yml
     */
    @Bean
    public RoutingFlagService routingFlagService(ReactiveRedisTemplate<String, String> redisTemplate) {
        return new RoutingFlagService(
                redisTemplate,
                cacheTtlSeconds,
                redisKeyPrefix,
                Map.of(
                        "routing.flag.account-service", accountServiceDefault,
                        "routing.flag.catalog-service", catalogServiceDefault,
                        "routing.flag.order-service", orderServiceDefault
                )
        );
    }

    /**
     * Service component providing cached, reactive access to per-service routing flags
     * stored in Redis. This is the core mechanism enabling the Strangler Fig pattern's
     * runtime-switchable routing.
     *
     * <h2>Caching Strategy</h2>
     * <p>Each flag value is cached locally in a {@link ConcurrentHashMap} with a configurable
     * TTL (default 1 second). This prevents a Redis round-trip on every gateway request while
     * ensuring near-real-time flag switching. When an operations team changes a Redis key value
     * from "monolith" to "microservice", ALL new requests will use the new target within the
     * cache TTL window.</p>
     *
     * <h2>Thread Safety</h2>
     * <p>The local cache uses {@link ConcurrentHashMap} because Spring Cloud Gateway processes
     * requests on multiple Netty event loop threads concurrently. Individual cache entry reads
     * and writes are thread-safe. A brief window of stale reads during cache entry expiration
     * is acceptable — the worst case is one extra request routed to the old target.</p>
     *
     * <h2>Graceful Degradation</h2>
     * <p>If Redis is unavailable or the key does not exist:
     * <ul>
     *   <li>{@code defaultIfEmpty()} handles missing Redis keys by returning the configured default</li>
     *   <li>{@code onErrorReturn()} handles Redis connection failures by returning the configured default</li>
     *   <li>All defaults are "monolith" — ensuring the system safely routes all traffic to the
     *       monolith when Redis is down</li>
     * </ul>
     *
     * @see RoutingFlagConfig
     */
    public static class RoutingFlagService {

        /** Global fallback value when no default is configured for a specific flag key. */
        private static final String GLOBAL_DEFAULT = "monolith";

        private final ReactiveRedisTemplate<String, String> redisTemplate;
        private final Duration cacheTtl;
        private final String redisKeyPrefix;
        private final Map<String, String> defaults;
        private final Map<String, CachedFlag> localCache = new ConcurrentHashMap<>();

        /**
         * Constructs a new RoutingFlagService.
         *
         * @param redisTemplate   the reactive Redis template for non-blocking flag reads
         * @param cacheTtlSeconds the local cache TTL in seconds (default 1)
         * @param redisKeyPrefix  the Redis key prefix (default "routing.flag.")
         * @param defaults        map of flag key to default value (all "monolith" at startup)
         */
        public RoutingFlagService(ReactiveRedisTemplate<String, String> redisTemplate,
                                  int cacheTtlSeconds,
                                  String redisKeyPrefix,
                                  Map<String, String> defaults) {
            this.redisTemplate = redisTemplate;
            this.cacheTtl = Duration.ofSeconds(cacheTtlSeconds);
            this.redisKeyPrefix = redisKeyPrefix;
            this.defaults = defaults;
        }

        /**
         * Gets the routing flag value for a given flag key.
         *
         * <p>Returns a {@link Mono} emitting either {@code "monolith"} or {@code "microservice"}.
         * The value is read from the local cache if a non-expired entry exists; otherwise,
         * it is fetched from Redis, cached locally, and returned.
         *
         * <p>The reactive chain ensures fully non-blocking execution compatible with the
         * Netty event loop:
         * <ol>
         *   <li>Check local cache (O(1) ConcurrentHashMap lookup)</li>
         *   <li>If cache miss or expired: query Redis reactively</li>
         *   <li>If Redis key absent: return configured default via {@code defaultIfEmpty()}</li>
         *   <li>Cache the result locally for subsequent requests</li>
         *   <li>If Redis error: return configured default via {@code onErrorReturn()}</li>
         * </ol>
         *
         * @param flagKey the full Redis key for the routing flag
         *                (e.g., "routing.flag.account-service")
         * @return a Mono emitting "monolith" or "microservice"
         */
        public Mono<String> getFlag(String flagKey) {
            // Check local cache first — avoids Redis round-trip if entry is still valid
            CachedFlag cached = localCache.get(flagKey);
            if (cached != null && !cached.isExpired()) {
                return Mono.just(cached.value);
            }

            // Determine the default value for this flag key
            String defaultValue = defaults.getOrDefault(flagKey, GLOBAL_DEFAULT);

            // Fetch from Redis reactively, with graceful degradation
            return redisTemplate.opsForValue().get(flagKey)
                    .defaultIfEmpty(defaultValue)
                    .doOnNext(value -> localCache.put(flagKey, new CachedFlag(value, cacheTtl)))
                    .onErrorReturn(defaultValue);
        }

        /**
         * Returns the configured Redis key prefix for routing flags.
         * Useful for constructing full flag keys from service identifiers.
         *
         * @return the Redis key prefix (e.g., "routing.flag.")
         */
        public String getRedisKeyPrefix() {
            return redisKeyPrefix;
        }

        /**
         * Returns the configured cache TTL duration.
         * Useful for monitoring and diagnostic purposes.
         *
         * @return the cache TTL as a Duration
         */
        public Duration getCacheTtl() {
            return cacheTtl;
        }

        /**
         * Thread-safe local cache entry holding a routing flag value and its expiration time.
         *
         * <p>Each entry stores the flag value (e.g., "monolith" or "microservice") along with
         * an absolute expiration timestamp in milliseconds. Once expired, the next call to
         * {@link RoutingFlagService#getFlag(String)} will refresh the value from Redis.
         *
         * <p>This class is intentionally simple and immutable after construction to minimize
         * concurrency concerns in the ConcurrentHashMap-based cache.
         */
        private static class CachedFlag {

            /** The cached routing flag value ("monolith" or "microservice"). */
            final String value;

            /** Absolute expiration time in milliseconds since epoch. */
            final long expiresAt;

            /**
             * Creates a new cache entry with the given value and TTL.
             *
             * @param value the routing flag value to cache
             * @param ttl   the time-to-live duration for this cache entry
             */
            CachedFlag(String value, Duration ttl) {
                this.value = value;
                this.expiresAt = System.currentTimeMillis() + ttl.toMillis();
            }

            /**
             * Checks whether this cache entry has expired.
             *
             * @return true if the current time exceeds the entry's expiration time
             */
            boolean isExpired() {
                return System.currentTimeMillis() > expiresAt;
            }
        }
    }
}
