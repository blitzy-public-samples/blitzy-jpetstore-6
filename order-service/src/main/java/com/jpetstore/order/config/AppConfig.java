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

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * General Spring Boot configuration class for the Order Service microservice.
 *
 * <p>This configuration class replaces the monolith's XML-based
 * {@code applicationContext.xml} for the Order bounded context. The monolith
 * configured an embedded HSQLDB datasource, a MyBatis {@code SqlSessionFactory}
 * with type aliases for {@code org.mybatis.jpetstore.domain}, component scanning
 * of the service package, mapper scanning for MyBatis interfaces, and a
 * {@code DataSourceTransactionManager} for JDBC local transactions — all via
 * Spring XML bean definitions.</p>
 *
 * <p>In the decomposed microservices architecture, Spring Boot 3 auto-configuration
 * replaces all of those XML-defined beans:</p>
 * <ul>
 *   <li><b>DataSource</b> — auto-configured from {@code spring.datasource.*}
 *       properties in {@code application.yml} (PostgreSQL via HikariCP)</li>
 *   <li><b>EntityManagerFactory</b> — auto-configured from {@code spring.jpa.*}
 *       properties (Hibernate ORM replaces MyBatis)</li>
 *   <li><b>TransactionManager</b> — auto-configured as {@code JpaTransactionManager}
 *       (replaces {@code DataSourceTransactionManager})</li>
 *   <li><b>Component scanning</b> — handled by {@code @SpringBootApplication} on
 *       the main class, scanning {@code com.jpetstore.order}</li>
 *   <li><b>Repository detection</b> — Spring Data JPA auto-detects
 *       {@code @Repository} interfaces (replaces MyBatis mapper scanning)</li>
 *   <li><b>Schema management</b> — Liquibase manages the PostgreSQL schema
 *       via changelogs (replaces HSQLDB schema and data SQL scripts)</li>
 * </ul>
 *
 * <p>This class is intentionally minimal — it only defines beans that Spring Boot
 * auto-configuration does not provide: specifically, named {@link RestClient}
 * beans for inter-service REST communication with the Account Service and
 * Catalog Service. These clients enable the Order Service to:</p>
 * <ul>
 *   <li>Verify user accounts via Account Service
 *       ({@code GET /api/accounts/{username}})</li>
 *   <li>Retrieve item details from Catalog Service
 *       ({@code GET /api/items/{id}})</li>
 *   <li>Decrement inventory during Saga order placement
 *       ({@code POST /api/items/{id}/inventory/decrement})</li>
 *   <li>Restore inventory on order failure (compensating action)
 *       ({@code POST /api/items/{id}/inventory/restore})</li>
 * </ul>
 *
 * <p><b>Note:</b> Redis configuration is in {@code RedisConfig.java}, and
 * dual-write coexistence configuration is in {@code DualWriteConfig.java}.
 * Neither is included here to maintain single-responsibility separation.</p>
 *
 * @see com.jpetstore.order.client.AccountServiceClient
 * @see com.jpetstore.order.client.CatalogServiceClient
 * @see com.jpetstore.order.saga.OrderSagaOrchestrator
 */
@Configuration
public class AppConfig {

    /**
     * HTTP connection timeout in milliseconds for inter-service REST calls.
     * Injected from {@code services.connect-timeout-ms} in application.yml.
     * Defaults to 5000ms (5 seconds) if not specified — sufficient for
     * establishing TCP connections to co-located Docker services while
     * protecting against network partitions.
     */
    @Value("${services.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    /**
     * HTTP read timeout in milliseconds for inter-service REST calls.
     * Injected from {@code services.read-timeout-ms} in application.yml.
     * Defaults to 10000ms (10 seconds) if not specified — set higher than
     * the connect timeout to accommodate order processing operations that
     * involve multiple database operations on the target service (e.g.,
     * Catalog Service inventory decrement with optimistic locking retries).
     */
    @Value("${services.read-timeout-ms:10000}")
    private int readTimeoutMs;

    /**
     * Creates a {@link RestClient} bean configured for the Account Service.
     *
     * <p>This client is used by {@code AccountServiceClient} to verify user
     * accounts during order placement. In the monolith, this data was accessed
     * via session-scoped {@code AccountActionBean} — specifically,
     * {@code OrderActionBean.newOrderForm()} retrieved the account from the
     * HTTP session via {@code session.getAttribute("/actions/Account.action")}.
     * The microservices architecture replaces that session coupling with a
     * REST call to the Account Service.</p>
     *
     * <p>Endpoints called via this client:</p>
     * <ul>
     *   <li>{@code GET /api/accounts/{username}} — verify account exists
     *       during order placement</li>
     * </ul>
     *
     * @param baseUrl the Account Service base URL, injected from
     *                {@code services.account-service.url} in application.yml
     *                (default: {@code http://account-service:8081})
     * @return a configured {@link RestClient} instance for Account Service
     *         communication with timeout settings applied
     */
    @Bean
    public RestClient accountServiceRestClient(
            @Value("${services.account-service.url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(createRequestFactory())
                .build();
    }

    /**
     * Creates a {@link RestClient} bean configured for the Catalog Service.
     *
     * <p>This client is used by {@code CatalogServiceClient} and the
     * {@code OrderSagaOrchestrator} for inventory management during order
     * placement. In the monolith, inventory was decremented directly via
     * {@code itemMapper.updateInventoryQuantity(param)} within a single
     * {@code @Transactional} method in {@code OrderService.insertOrder()}.
     * In the decomposed architecture, this cross-bounded-context operation
     * is replaced by REST calls coordinated via the Saga pattern.</p>
     *
     * <p>Endpoints called via this client:</p>
     * <ul>
     *   <li>{@code GET /api/items/{id}} — retrieve item details for order
     *       line items</li>
     *   <li>{@code POST /api/items/{id}/inventory/decrement} — Saga forward
     *       step: reserve/decrement inventory for each line item</li>
     *   <li>{@code POST /api/items/{id}/inventory/restore} — Saga compensating
     *       action: restore inventory on order failure</li>
     * </ul>
     *
     * <p><b>Critical:</b> This client is essential for the orchestration-based
     * Saga pattern. Timeouts are configured generously (5s connect, 10s read)
     * to ensure inventory operations complete successfully during the multi-step
     * order transaction. The read timeout of 10 seconds accommodates potential
     * optimistic locking retries on the Catalog Service side.</p>
     *
     * @param baseUrl the Catalog Service base URL, injected from
     *                {@code services.catalog-service.url} in application.yml
     *                (default: {@code http://catalog-service:8082})
     * @return a configured {@link RestClient} instance for Catalog Service
     *         communication with timeout settings applied
     */
    @Bean
    public RestClient catalogServiceRestClient(
            @Value("${services.catalog-service.url}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .requestFactory(createRequestFactory())
                .build();
    }

    /**
     * Creates a {@link SimpleClientHttpRequestFactory} with configured timeouts
     * for inter-service HTTP communication.
     *
     * <p>Both the Account Service and Catalog Service REST clients share the
     * same timeout configuration to ensure consistent behavior across all
     * outbound HTTP calls:</p>
     * <ul>
     *   <li><b>Connection timeout:</b> 5 seconds (default) — maximum time to
     *       establish a TCP connection to the target service. This is sufficient
     *       for co-located Docker services while protecting against network
     *       partitions or unreachable hosts.</li>
     *   <li><b>Read timeout:</b> 10 seconds (default) — maximum time to wait
     *       for a response after the request is sent. Set higher than the
     *       connection timeout to accommodate multi-step database operations
     *       on the target service (e.g., Catalog Service inventory decrement
     *       with optimistic locking retries).</li>
     * </ul>
     *
     * <p>Timeout values can be overridden via {@code application.yml}:</p>
     * <pre>
     * services:
     *   connect-timeout-ms: 5000
     *   read-timeout-ms: 10000
     * </pre>
     *
     * @return a configured {@link SimpleClientHttpRequestFactory} with
     *         connection and read timeout settings applied
     */
    private SimpleClientHttpRequestFactory createRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }
}
