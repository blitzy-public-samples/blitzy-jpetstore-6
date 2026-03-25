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
package com.jpetstore.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 3 main application class for the Order Service microservice.
 *
 * <p>This class serves as the entry point for the Order/Cart bounded context,
 * which is the third and final service to be cut over in the Strangler Fig
 * migration pattern from the JPetStore 6 monolith. It replaces the monolith's
 * XML-based Spring context ({@code applicationContext.xml}) for the order bounded
 * context, transitioning from embedded HSQLDB with MyBatis mapper XML definitions
 * to Spring Boot auto-configuration with PostgreSQL and Spring Data JPA.</p>
 *
 * <p>The {@link SpringBootApplication @SpringBootApplication} composite annotation enables:</p>
 * <ul>
 *   <li><strong>Auto-configuration</strong> — automatically configures Spring Data JPA,
 *       PostgreSQL datasource, Liquibase schema management, Spring Data Redis for
 *       externalized cart state, embedded Tomcat, and Actuator health endpoints
 *       based on classpath dependencies</li>
 *   <li><strong>Component scanning</strong> — discovers all Spring-managed beans in
 *       {@code com.jpetstore.order} and all sub-packages including:
 *       <ul>
 *         <li>{@code com.jpetstore.order.controller} — REST API controllers
 *             (OrderController, CartController)</li>
 *         <li>{@code com.jpetstore.order.service} — Business logic services
 *             (OrderService, CartStateService, OrderSagaOrchestrator)</li>
 *         <li>{@code com.jpetstore.order.repository} — Spring Data JPA repositories
 *             (OrderRepository, OrderStatusRepository, LineItemRepository,
 *              CartStateRepository)</li>
 *         <li>{@code com.jpetstore.order.entity} — JPA entity classes
 *             (Order, OrderStatus, LineItem, CartState)</li>
 *         <li>{@code com.jpetstore.order.dto} — Data transfer objects
 *             (OrderDTO, OrderRequest, CartDTO, CartItemDTO)</li>
 *         <li>{@code com.jpetstore.order.client} — REST clients for inter-service
 *             communication (AccountServiceClient, CatalogServiceClient)</li>
 *         <li>{@code com.jpetstore.order.saga} — Saga orchestration components
 *             (OrderSagaState, OrderSagaStep, InventoryCompensation)</li>
 *         <li>{@code com.jpetstore.order.config} — Configuration classes
 *             (AppConfig, RedisConfig, DualWriteConfig)</li>
 *       </ul>
 *   </li>
 *   <li><strong>Configuration properties</strong> — binds externalized configuration
 *       from {@code application.yml} including PostgreSQL datasource settings,
 *       Redis connection parameters, JPA/Hibernate properties, Liquibase changelog
 *       paths, inter-service URLs, and Actuator endpoints</li>
 * </ul>
 *
 * <p><strong>Owned database tables</strong> (Order/Cart bounded context):</p>
 * <ul>
 *   <li>{@code orders} — Order header records with shipping, billing, and payment details</li>
 *   <li>{@code orderstatus} — Per-line-item order status tracking</li>
 *   <li>{@code lineitem} — Order line items with quantity and unit price</li>
 *   <li>{@code order_id_seq} — PostgreSQL sequence replacing the monolith's non-thread-safe
 *       HSQLDB sequence table</li>
 * </ul>
 *
 * <p><strong>Externalized state</strong>:</p>
 * <ul>
 *   <li>Cart state is managed via Redis ({@code CartState} entity with
 *       {@code @RedisHash("cart")}), replacing the monolith's session-scoped
 *       {@code CartActionBean.cart} field</li>
 * </ul>
 *
 * <p><strong>Cross-service dependencies</strong>:</p>
 * <ul>
 *   <li>Account Service — user verification via {@code GET /api/accounts/{username}}</li>
 *   <li>Catalog Service — inventory reservation via
 *       {@code POST /api/items/{id}/inventory/decrement} and item details via
 *       {@code GET /api/items/{id}}</li>
 * </ul>
 *
 * <p><strong>Design notes</strong>:</p>
 * <ul>
 *   <li>No {@code @EnableJpaRepositories} annotation is needed — Spring Boot
 *       auto-detects Spring Data JPA repositories in scanned packages</li>
 *   <li>No {@code @EnableRedisRepositories} annotation is needed — Spring Boot
 *       auto-detects Redis repositories when Spring Data Redis is on the classpath</li>
 *   <li>No {@code @EnableDiscoveryClient} — static routing via API Gateway,
 *       not service discovery</li>
 *   <li>No MyBatis, HSQLDB, or Stripes framework references — this service
 *       uses exclusively Jakarta EE 10 namespace (Spring Boot 3.x)</li>
 *   <li>Uses PostgreSQL as the sole relational database backend per the
 *       database-per-service pattern; HSQLDB is prohibited in new services</li>
 * </ul>
 *
 * @see org.springframework.boot.autoconfigure.SpringBootApplication
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public class OrderServiceApplication {

    /**
     * Main entry point for the Order Service microservice.
     *
     * <p>Bootstraps the Spring Boot application context, starts the embedded
     * Tomcat server, initializes the PostgreSQL datasource connection pool,
     * establishes Redis connection for cart state management, runs Liquibase
     * schema migrations, and begins accepting REST API requests on the
     * configured server port (default: 8083).</p>
     *
     * @param args command-line arguments passed to the Spring Boot application;
     *             supports standard Spring Boot externalized configuration overrides
     *             (e.g., {@code --server.port=8083}, {@code --spring.profiles.active=prod})
     */
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
