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
package com.jpetstore.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for the Order Service's externalized cart state.
 *
 * <p>This configuration class sets up the {@link RedisTemplate} bean required by
 * {@code CartStateService} to manage cart state in Redis, replacing the monolith's
 * HTTP session-scoped {@code CartActionBean} storage pattern. In the original monolith,
 * cart state was stored in-process using a {@code @SessionScope} annotation on
 * {@code CartActionBean}, which held a {@code Cart} object backed by
 * {@code Collections.synchronizedMap(new HashMap<>())} and an {@code ArrayList<CartItem>}.
 * This approach coupled cart state to a single JVM instance, preventing horizontal
 * scaling and requiring sticky sessions.</p>
 *
 * <h3>Externalized Cart State Architecture</h3>
 * <p>In the microservices architecture, cart state is externalized to Redis using a
 * hash-based data structure. Each cart is keyed by either:</p>
 * <ul>
 *   <li><strong>Session cookie</strong> ({@code cart:{sessionId}}) for anonymous/unauthenticated
 *       users — enables the critical constraint that unauthenticated users can browse and
 *       build a cart before signing in</li>
 *   <li><strong>Username</strong> ({@code cart:user:{username}}) for authenticated users —
 *       on login, the anonymous cart is merged into the user's persistent cart</li>
 * </ul>
 *
 * <h3>Redis Hash Data Structure</h3>
 * <pre>
 * Key:   "cart:{sessionId}" or "cart:user:{username}"
 * Type:  Hash
 * Fields:
 *   "EST-1" → {"itemId":"EST-1","productId":"FI-SW-01","quantity":2,"unitPrice":16.50,"inStock":true}
 *   "EST-2" → {"itemId":"EST-2","productId":"FI-SW-02","quantity":1,"unitPrice":16.50,"inStock":true}
 * </pre>
 *
 * <h3>TTL Strategy</h3>
 * <p>Anonymous carts expire after a configurable duration (default: 24 hours) controlled by
 * the {@code cart.session.ttl-hours} property in {@code application.yml}. Authenticated user
 * carts have a longer TTL or are persistent. The actual TTL application is handled by
 * {@code CartStateService} when setting Redis keys — this configuration class provides
 * the TTL value for injection into service-layer beans.</p>
 *
 * <h3>Connection Configuration</h3>
 * <p>The {@link RedisConnectionFactory} is auto-configured by Spring Boot from properties
 * in {@code application.yml}:</p>
 * <ul>
 *   <li>{@code spring.data.redis.host} — Redis server hostname (default: {@code redis}
 *       for Docker Compose networking)</li>
 *   <li>{@code spring.data.redis.port} — Redis server port (default: {@code 6379})</li>
 * </ul>
 * <p>The auto-configured factory uses the Lettuce Redis client (included transitively
 * via {@code spring-boot-starter-data-redis}).</p>
 *
 * @see org.springframework.data.redis.core.RedisTemplate
 * @see org.springframework.data.redis.connection.RedisConnectionFactory
 */
@Configuration
public class RedisConfig {

    /**
     * Anonymous cart time-to-live in hours.
     *
     * <p>Injected from {@code cart.session.ttl-hours} in {@code application.yml}.
     * Defaults to 24 hours if the property is not defined. This value is available
     * for injection into {@code CartStateService} and other beans that need to apply
     * expiration policies to anonymous cart keys in Redis.</p>
     *
     * <p>Anonymous carts (keyed by session cookie) expire after this duration to
     * prevent unbounded Redis memory growth from abandoned shopping sessions.
     * Authenticated user carts (keyed by username) use a separate, longer TTL
     * or are persistent, as determined by {@code CartStateService}.</p>
     */
    @Value("${cart.session.ttl-hours:24}")
    private long cartSessionTtlHours;

    /**
     * Creates a {@link RedisTemplate} configured for externalized cart state operations.
     *
     * <p>This template is the primary Redis access abstraction used by {@code CartStateService}
     * for all cart CRUD operations (add item, remove item, update quantity, get cart, clear cart).
     * It replaces the monolith's in-memory {@code Collections.synchronizedMap(new HashMap<>())}
     * with a distributed, JSON-serialized Redis hash structure.</p>
     *
     * <h4>Serialization Strategy</h4>
     * <ul>
     *   <li><strong>Key serializer</strong>: {@link StringRedisSerializer} — encodes top-level
     *       Redis keys as UTF-8 strings (e.g., {@code "cart:abc123"}, {@code "cart:user:j2ee"})</li>
     *   <li><strong>Value serializer</strong>: {@link GenericJackson2JsonRedisSerializer} — serializes
     *       cart state objects ({@code CartState}, {@code CartItemDTO}) as JSON, enabling human-readable
     *       inspection and cross-language compatibility</li>
     *   <li><strong>Hash key serializer</strong>: {@link StringRedisSerializer} — encodes hash field
     *       names as strings (e.g., item IDs like {@code "EST-1"}, {@code "EST-14"})</li>
     *   <li><strong>Hash value serializer</strong>: {@link GenericJackson2JsonRedisSerializer} —
     *       serializes individual cart item details as JSON hash values</li>
     * </ul>
     *
     * <p>The {@link GenericJackson2JsonRedisSerializer} includes type information ({@code @class})
     * in the serialized JSON, enabling polymorphic deserialization without requiring the caller
     * to specify the target type at read time.</p>
     *
     * @param connectionFactory the auto-configured {@link RedisConnectionFactory} provided by
     *                          Spring Boot from {@code spring.data.redis.*} properties in
     *                          {@code application.yml}; uses Lettuce as the underlying client
     * @return a fully configured {@link RedisTemplate} ready for cart state operations
     */
    @Bean
    public RedisTemplate<String, Object> cartRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();

        // Set the connection factory — auto-configured by Spring Boot from application.yml
        // properties: spring.data.redis.host (default: "redis") and spring.data.redis.port (default: 6379)
        template.setConnectionFactory(connectionFactory);

        // Key serializer: StringRedisSerializer for cart keys like "cart:{sessionId}" or "cart:user:{username}"
        // This ensures Redis keys are stored as human-readable UTF-8 strings rather than
        // JDK-serialized byte arrays, enabling direct inspection via redis-cli
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);

        // Value serializer: GenericJackson2JsonRedisSerializer for cart state objects
        // Serializes CartState and CartItemDTO as JSON with type metadata (@class field),
        // enabling polymorphic deserialization and human-readable storage
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);

        // Hash key serializer: StringRedisSerializer for item ID hash fields (e.g., "EST-1", "EST-14")
        // When using Redis hashes (HSET/HGET), the field names are serialized with this serializer
        template.setHashKeySerializer(stringSerializer);

        // Hash value serializer: GenericJackson2JsonRedisSerializer for cart item detail JSON values
        // Each hash value contains the full cart item representation:
        // {"itemId":"EST-1","productId":"FI-SW-01","quantity":2,"unitPrice":16.50,"inStock":true}
        template.setHashValueSerializer(jsonSerializer);

        // Initialize the template — validates configuration and prepares internal state
        // Must be called before the template is used; Spring typically calls this via
        // InitializingBean.afterPropertiesSet(), but calling it explicitly ensures
        // the template is fully ready before being returned from the factory method
        template.afterPropertiesSet();

        return template;
    }
}
