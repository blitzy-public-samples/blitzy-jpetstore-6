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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link RoutingFlagConfig.RoutingFlagService} — the Redis-backed
 * routing flag service that powers the Strangler Fig runtime-switchable routing.
 *
 * <p>Verifies the following behaviors:
 * <ul>
 *   <li>Reading routing flag values from Redis</li>
 *   <li>Defaulting to "monolith" when Redis keys are absent</li>
 *   <li>Gracefully falling back to "monolith" when Redis connection fails</li>
 *   <li>Local caching with 1-second TTL to reduce Redis round-trips</li>
 *   <li>Independent caching per flag key</li>
 *   <li>Cache expiry and refresh behavior</li>
 *   <li>Unknown flag keys default to "monolith" via GLOBAL_DEFAULT</li>
 * </ul>
 *
 * <p>All reactive {@link Mono} assertions use {@link StepVerifier} from reactor-test.
 * No blocking {@code .block()} calls are used to avoid potential event loop deadlocks.
 *
 * <p>This is a pure unit test using Mockito — no Spring context is loaded.
 *
 * @see RoutingFlagConfig
 * @see RoutingFlagConfig.RoutingFlagService
 */
@ExtendWith(MockitoExtension.class)
class RoutingFlagConfigTest {

    /** Mocked reactive Redis template — simulates Redis interactions without a real connection. */
    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    /** Mocked reactive value operations — returned by redisTemplate.opsForValue(). */
    @Mock
    private ReactiveValueOperations<String, String> valueOperations;

    /** The service under test, constructed directly with mocked dependencies. */
    private RoutingFlagConfig.RoutingFlagService routingFlagService;

    /**
     * Sets up the test environment before each test method.
     *
     * <p>Stubs {@code redisTemplate.opsForValue()} to return the mocked value operations,
     * then constructs a {@link RoutingFlagConfig.RoutingFlagService} with:
     * <ul>
     *   <li>Mocked ReactiveRedisTemplate</li>
     *   <li>1-second cache TTL (per AAP Section 0.7.6)</li>
     *   <li>"routing.flag." Redis key prefix</li>
     *   <li>Default map with all three service flags set to "monolith"</li>
     * </ul>
     */
    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Map<String, String> defaults = Map.of(
                "routing.flag.account-service", "monolith",
                "routing.flag.catalog-service", "monolith",
                "routing.flag.order-service", "monolith"
        );

        routingFlagService = new RoutingFlagConfig.RoutingFlagService(
                redisTemplate, 1, "routing.flag.", defaults
        );
    }

    // -----------------------------------------------------------------------
    // Redis Flag Reading Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that when a routing flag exists in Redis, its value is returned.
     *
     * <p>This tests the primary happy path: Redis has a key with value "microservice"
     * and the service returns that value without falling back to the default.
     */
    @Test
    void shouldReturnFlagValueFromRedis() {
        // given — Redis has "microservice" for the catalog-service flag
        when(valueOperations.get("routing.flag.catalog-service"))
                .thenReturn(Mono.just("microservice"));

        // when — request the flag value
        Mono<String> result = routingFlagService.getFlag("routing.flag.catalog-service");

        // then — the Redis value is returned
        StepVerifier.create(result)
                .expectNext("microservice")
                .verifyComplete();
    }

    /**
     * Verifies that when a Redis key is absent (empty Mono), the service returns "monolith".
     *
     * <p>This tests the {@code defaultIfEmpty("monolith")} behavior defined in
     * AAP Section 0.7.6 — absent keys always route to the monolith.
     */
    @Test
    void shouldReturnMonolithWhenRedisKeyAbsent() {
        // given — Redis does not have the account-service flag key
        when(valueOperations.get("routing.flag.account-service"))
                .thenReturn(Mono.empty());

        // when — request the flag value
        Mono<String> result = routingFlagService.getFlag("routing.flag.account-service");

        // then — default "monolith" is returned
        StepVerifier.create(result)
                .expectNext("monolith")
                .verifyComplete();
    }

    /**
     * Verifies that when Redis connection fails, the service gracefully falls back to "monolith".
     *
     * <p>This tests the {@code onErrorReturn("monolith")} behavior defined in
     * AAP Section 0.8.2 — Redis failures must NEVER break routing. The system
     * safely falls back to the monolith for all traffic.
     */
    @Test
    void shouldReturnMonolithWhenRedisConnectionFails() {
        // given — Redis connection throws an error
        when(valueOperations.get("routing.flag.order-service"))
                .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        // when — request the flag value
        Mono<String> result = routingFlagService.getFlag("routing.flag.order-service");

        // then — graceful fallback to "monolith"
        StepVerifier.create(result)
                .expectNext("monolith")
                .verifyComplete();
    }

    /**
     * Verifies that all three bounded context flag keys default to "monolith"
     * when their Redis keys are absent.
     *
     * <p>Covers all three flags defined in AAP Section 0.7.6:
     * <ul>
     *   <li>{@code routing.flag.account-service}</li>
     *   <li>{@code routing.flag.catalog-service}</li>
     *   <li>{@code routing.flag.order-service}</li>
     * </ul>
     */
    @Test
    void shouldDefaultToMonolithForAllThreeFlagKeys() {
        // given — all three Redis keys are absent
        when(valueOperations.get("routing.flag.account-service"))
                .thenReturn(Mono.empty());
        when(valueOperations.get("routing.flag.catalog-service"))
                .thenReturn(Mono.empty());
        when(valueOperations.get("routing.flag.order-service"))
                .thenReturn(Mono.empty());

        // then — each flag key returns "monolith" as default
        StepVerifier.create(routingFlagService.getFlag("routing.flag.account-service"))
                .expectNext("monolith")
                .verifyComplete();

        StepVerifier.create(routingFlagService.getFlag("routing.flag.catalog-service"))
                .expectNext("monolith")
                .verifyComplete();

        StepVerifier.create(routingFlagService.getFlag("routing.flag.order-service"))
                .expectNext("monolith")
                .verifyComplete();
    }

    // -----------------------------------------------------------------------
    // Local Cache Behavior Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that a cached value is reused within the TTL window without calling Redis again.
     *
     * <p>Tests the local cache hit path: after the first call populates the cache,
     * a second call within the 1-second TTL should return the cached value without
     * making a second Redis round-trip.
     */
    @Test
    void shouldUseCachedValueWithinTtl() {
        // given — Redis returns "microservice" for catalog-service
        when(valueOperations.get("routing.flag.catalog-service"))
                .thenReturn(Mono.just("microservice"));

        // when — first call populates the cache
        StepVerifier.create(routingFlagService.getFlag("routing.flag.catalog-service"))
                .expectNext("microservice")
                .verifyComplete();

        // when — second call should use cache (within 1-second TTL)
        StepVerifier.create(routingFlagService.getFlag("routing.flag.catalog-service"))
                .expectNext("microservice")
                .verifyComplete();

        // then — Redis was called only once; second call used cache
        verify(valueOperations, times(1)).get("routing.flag.catalog-service");
    }

    /**
     * Verifies that after the cache TTL expires, the next call fetches a fresh value from Redis.
     *
     * <p>This test constructs a separate {@link RoutingFlagConfig.RoutingFlagService} with
     * {@code cacheTtlSeconds=0} so that cache entries expire immediately. Two consecutive
     * calls to {@code getFlag()} should each trigger a separate Redis lookup, and each
     * should return the value that Redis provides at that moment.
     *
     * @throws InterruptedException if the thread sleep is interrupted
     */
    @Test
    void shouldRefreshCacheAfterTtlExpiry() throws InterruptedException {
        // given — a service with TTL=0 so cache is always expired
        Map<String, String> defaults = Map.of(
                "routing.flag.account-service", "monolith",
                "routing.flag.catalog-service", "monolith",
                "routing.flag.order-service", "monolith"
        );
        RoutingFlagConfig.RoutingFlagService zeroTtlService =
                new RoutingFlagConfig.RoutingFlagService(redisTemplate, 0, "routing.flag.", defaults);

        // Verify the zero-TTL configuration is applied correctly
        assertThat(zeroTtlService.getCacheTtl()).isEqualTo(Duration.ofSeconds(0));

        // given — Redis returns different values on consecutive calls
        when(valueOperations.get("routing.flag.catalog-service"))
                .thenReturn(Mono.just("microservice"))
                .thenReturn(Mono.just("monolith"));

        // when — first call returns "microservice" from Redis
        StepVerifier.create(zeroTtlService.getFlag("routing.flag.catalog-service"))
                .expectNext("microservice")
                .verifyComplete();

        // Allow cache entry to expire (TTL=0, needs > 0ms to be considered expired)
        Thread.sleep(2);

        // when — second call (cache expired) returns fresh value from Redis
        StepVerifier.create(zeroTtlService.getFlag("routing.flag.catalog-service"))
                .expectNext("monolith")
                .verifyComplete();

        // then — Redis was called twice (once per cache miss)
        verify(valueOperations, times(2)).get("routing.flag.catalog-service");
    }

    /**
     * Verifies that different flag keys maintain independent caches.
     *
     * <p>Each bounded context flag key should have its own cache entry, so changing
     * one flag does not affect the cached value of another. This prevents cross-service
     * cache pollution in the Strangler Fig routing mechanism.
     */
    @Test
    void shouldMaintainIndependentCachesPerFlagKey() {
        // given — different Redis values for different flag keys
        when(valueOperations.get("routing.flag.account-service"))
                .thenReturn(Mono.just("monolith"));
        when(valueOperations.get("routing.flag.catalog-service"))
                .thenReturn(Mono.just("microservice"));

        // when/then — account-service returns "monolith"
        StepVerifier.create(routingFlagService.getFlag("routing.flag.account-service"))
                .expectNext("monolith")
                .verifyComplete();

        // when/then — catalog-service returns "microservice" (independent cache)
        StepVerifier.create(routingFlagService.getFlag("routing.flag.catalog-service"))
                .expectNext("microservice")
                .verifyComplete();

        // Verify each key was queried from Redis independently
        verify(valueOperations, times(1)).get("routing.flag.account-service");
        verify(valueOperations, times(1)).get("routing.flag.catalog-service");

        // Second call to each should use cache, not Redis
        StepVerifier.create(routingFlagService.getFlag("routing.flag.account-service"))
                .expectNext("monolith")
                .verifyComplete();
        StepVerifier.create(routingFlagService.getFlag("routing.flag.catalog-service"))
                .expectNext("microservice")
                .verifyComplete();

        // Redis still only called once per key — confirming independent caches
        verify(valueOperations, times(1)).get("routing.flag.account-service");
        verify(valueOperations, times(1)).get("routing.flag.catalog-service");
    }

    // -----------------------------------------------------------------------
    // Edge Case Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that an unknown flag key (not in the defaults map) returns "monolith"
     * via the GLOBAL_DEFAULT fallback.
     *
     * <p>The {@code defaults.getOrDefault(flagKey, GLOBAL_DEFAULT)} mechanism ensures
     * that any unrecognized flag key safely defaults to "monolith", preventing routing
     * to non-existent microservices.
     */
    @Test
    void shouldReturnMonolithForUnknownFlagKey() {
        // given — Redis does not have the unknown flag key
        when(valueOperations.get("routing.flag.unknown-service"))
                .thenReturn(Mono.empty());

        // when — request a flag for an unregistered service
        Mono<String> result = routingFlagService.getFlag("routing.flag.unknown-service");

        // then — falls back to GLOBAL_DEFAULT "monolith"
        StepVerifier.create(result)
                .expectNext("monolith")
                .verifyComplete();
    }
}
