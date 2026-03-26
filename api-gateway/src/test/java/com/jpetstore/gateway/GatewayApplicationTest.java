/*
 *    Copyright 2024 the original author or authors.
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
package com.jpetstore.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Spring Boot application context smoke test for the JPetStore API Gateway.
 *
 * <p>This foundational test verifies that the full Spring Boot application context
 * loads successfully with all {@code @Configuration} classes ({@code RouteConfig},
 * {@code RoutingFlagConfig}, {@code SecurityConfig}) and {@code @Component} classes
 * ({@code AuthenticationFilter}, {@code RoutingFlagFilter}) wired correctly.</p>
 *
 * <p>Uses {@code WebEnvironment.MOCK} to create a mock reactive web environment
 * (Netty/WebFlux) without starting a real server — sufficient for context load
 * verification.</p>
 *
 * <p>Redis auto-configuration ({@code RedisAutoConfiguration} and
 * {@code RedisReactiveAutoConfiguration}) is excluded to prevent the test from
 * requiring a running Redis server. The {@link ReactiveRedisConnectionFactory} is
 * provided as a {@code @MockitoBean} mock, which satisfies the dependency in
 * {@code RoutingFlagConfig} for creating the {@code ReactiveRedisTemplate} bean.
 * Excluding the auto-configuration also eliminates the duplicate
 * {@code ReactiveRedisTemplate} bean conflict between the custom
 * {@code reactiveRedisTemplate} in {@code RoutingFlagConfig} and the auto-configured
 * {@code reactiveStringRedisTemplate}.</p>
 *
 * @see GatewayApplication
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                        + "org.springframework.cloud.gateway.config.GatewayRedisAutoConfiguration"
        }
)
class GatewayApplicationTest {

    @Autowired
    private ApplicationContext context;

    /**
     * Mocks the reactive Redis connection factory to satisfy the dependency in
     * {@code RoutingFlagConfig.reactiveRedisTemplate(ReactiveRedisConnectionFactory)}.
     * With Redis auto-configuration excluded, this mock is the sole provider of the
     * connection factory bean, preventing any actual Redis connection attempts during
     * context loading. The {@code RoutingFlagConfig} uses this mock to construct
     * the {@code ReactiveRedisTemplate}, which in turn is injected into
     * {@code RoutingFlagService} — all without requiring a running Redis server.
     */
    @MockitoBean
    private ReactiveRedisConnectionFactory reactiveRedisConnectionFactory;

    /**
     * Verifies that the Spring Boot application context loads successfully.
     *
     * <p>This smoke test ensures that all auto-configuration, {@code @Configuration}
     * classes ({@code RouteConfig}, {@code RoutingFlagConfig}, {@code SecurityConfig}),
     * and {@code @Component} classes wire together without errors. A non-null context
     * confirms that all bean definitions are valid and all dependencies are satisfied.</p>
     */
    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    /**
     * Verifies that the {@link GatewayApplication} bean is registered in the
     * Spring application context.
     *
     * <p>This confirms that the {@code @SpringBootApplication} entry point class
     * is correctly detected by component scanning and registered as a bean,
     * which is a prerequisite for all downstream configuration classes to be
     * processed.</p>
     */
    @Test
    void gatewayApplicationBeanExists() {
        assertThat(context.getBean(GatewayApplication.class)).isNotNull();
    }
}
