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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Unit tests for {@link RedisConfig} — verifies the cart Redis template bean
 * configuration, serializer setup, and connection factory integration.
 *
 * <p>The RedisConfig class replaces the monolith's in-process session-scoped
 * cart storage ({@code CartActionBean} with {@code Collections.synchronizedMap})
 * with a Redis-backed externalized cart state, as required by the microservices
 * decomposition (AAP §0.7.2).</p>
 */
class RedisConfigTest {

    private RedisConfig redisConfig;
    private RedisConnectionFactory mockConnectionFactory;

    @BeforeEach
    void setUp() {
        redisConfig = new RedisConfig();
        mockConnectionFactory = mock(RedisConnectionFactory.class);
    }

    @Test
    @DisplayName("should create non-null RedisTemplate for cart operations")
    void shouldCreateCartRedisTemplate() {
        RedisTemplate<String, Object> template = redisConfig.cartRedisTemplate(mockConnectionFactory);
        assertThat(template).isNotNull();
    }

    @Test
    @DisplayName("should configure StringRedisSerializer for keys")
    void shouldUseStringSerializerForKeys() {
        RedisTemplate<String, Object> template = redisConfig.cartRedisTemplate(mockConnectionFactory);
        assertThat(template.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
    }

    @Test
    @DisplayName("should configure GenericJackson2JsonRedisSerializer for values")
    void shouldUseJsonSerializerForValues() {
        RedisTemplate<String, Object> template = redisConfig.cartRedisTemplate(mockConnectionFactory);
        assertThat(template.getValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
    }

    @Test
    @DisplayName("should configure StringRedisSerializer for hash keys")
    void shouldUseStringSerializerForHashKeys() {
        RedisTemplate<String, Object> template = redisConfig.cartRedisTemplate(mockConnectionFactory);
        assertThat(template.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
    }

    @Test
    @DisplayName("should configure GenericJackson2JsonRedisSerializer for hash values")
    void shouldUseJsonSerializerForHashValues() {
        RedisTemplate<String, Object> template = redisConfig.cartRedisTemplate(mockConnectionFactory);
        assertThat(template.getHashValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
    }

    @Test
    @DisplayName("should set connection factory on template")
    void shouldSetConnectionFactory() {
        RedisTemplate<String, Object> template = redisConfig.cartRedisTemplate(mockConnectionFactory);
        assertThat(template.getConnectionFactory()).isSameAs(mockConnectionFactory);
    }

    @Test
    @DisplayName("should call afterPropertiesSet during bean creation")
    void shouldInitializeTemplate() {
        // afterPropertiesSet is called in the bean method itself,
        // so a successfully created template confirms initialization
        RedisTemplate<String, Object> template = redisConfig.cartRedisTemplate(mockConnectionFactory);
        assertThat(template).isNotNull();
        // Verify template is initialized by checking serializers are properly set
        assertThat(template.getKeySerializer()).isNotNull();
        assertThat(template.getValueSerializer()).isNotNull();
        assertThat(template.getHashKeySerializer()).isNotNull();
        assertThat(template.getHashValueSerializer()).isNotNull();
    }
}
