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
package com.jpetstore.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.jpetstore.catalog.controller.CategoryController;
import com.jpetstore.catalog.controller.ItemController;
import com.jpetstore.catalog.controller.ProductController;
import com.jpetstore.catalog.repository.CategoryRepository;
import com.jpetstore.catalog.repository.InventoryRepository;
import com.jpetstore.catalog.repository.ItemRepository;
import com.jpetstore.catalog.repository.ProductRepository;
import com.jpetstore.catalog.repository.SupplierRepository;
import com.jpetstore.catalog.service.CatalogService;
import com.jpetstore.catalog.service.InventoryService;

/**
 * Root-level integration test for the Catalog Service microservice.
 *
 * <p>This smoke test verifies that the entire Spring Boot application context
 * loads successfully — confirming that all beans (controllers, services,
 * repositories, entities, and configurations) are wired correctly. It catches
 * wiring errors, missing beans, misconfigured {@code @Entity} mappings, broken
 * {@code @ManyToOne} relationships, and invalid {@code application.yml} settings
 * before any functional test runs.</p>
 *
 * <h3>Architecture Context</h3>
 * <p>This test replaces the monolith's test infrastructure pattern where
 * {@code MapperTestContext.java} configured an embedded HSQLDB instance with
 * MyBatis {@code SqlSessionFactory}. In the decomposed Catalog Service:</p>
 * <ul>
 *   <li>Embedded HSQLDB → Testcontainers PostgreSQL 16</li>
 *   <li>MyBatis mapper interfaces → Spring Data JPA repositories</li>
 *   <li>XML-based {@code applicationContext.xml} → Spring Boot auto-configuration</li>
 *   <li>{@code @ContextConfiguration(MapperTestContext.class)} → {@code @SpringBootTest}</li>
 * </ul>
 *
 * <h3>Validation Coverage</h3>
 * <p>A successful run of this single test class validates:</p>
 * <ul>
 *   <li>5 JPA entities (Category, Product, Item, Inventory, Supplier, InventoryReservation)
 *       with correct {@code @Entity} mappings, column names, types, and relationships</li>
 *   <li>All Spring Data JPA repositories are auto-detected and instantiated</li>
 *   <li>All {@code @Service} beans are created with correct constructor injection</li>
 *   <li>All {@code @RestController} beans are created with correct service dependencies</li>
 *   <li>Hibernate {@code create-drop} successfully generates the schema from entity
 *       annotations, validating all {@code @ManyToOne} relationships (Product→Category,
 *       Item→Product, Item→Supplier) and the {@code @Version} field on Inventory</li>
 *   <li>Spring Security configuration ({@code SecurityConfig}) initializes correctly</li>
 *   <li>Application configuration ({@code AppConfig}, {@code DualWriteConfig}) loads</li>
 * </ul>
 *
 * <h3>Test Conventions</h3>
 * <ul>
 *   <li>Package-private class (no {@code public} modifier) — matches monolith's
 *       {@code CatalogServiceTest.java} convention</li>
 *   <li>AssertJ {@code assertThat} assertions — consistent with monolith test patterns</li>
 *   <li>No business logic tested — purely a wiring verification test</li>
 *   <li>No test data seeded — context loading doesn't require data</li>
 *   <li>No {@code @Transactional} — no database operations beyond schema creation</li>
 *   <li>Uses {@code jakarta.*} namespace exclusively (Spring Boot 3.5.x / Jakarta EE 10)</li>
 * </ul>
 *
 * @see CatalogServiceApplication
 * @see com.jpetstore.catalog.entity.Category
 * @see com.jpetstore.catalog.entity.Product
 * @see com.jpetstore.catalog.entity.Item
 * @see com.jpetstore.catalog.entity.Inventory
 * @see com.jpetstore.catalog.entity.Supplier
 */
@SpringBootTest
@Testcontainers
class CatalogServiceApplicationIT {

    /**
     * Testcontainers-managed PostgreSQL 16 instance that replaces the monolith's
     * embedded HSQLDB configured in {@code MapperTestContext.dataSource()}.
     *
     * <p>PostgreSQL 16 matches the AAP infrastructure dependency (Docker Hub
     * {@code postgres:16}). The container is {@code static} so it is shared
     * across all test methods in the class (JUnit 5 per-class lifecycle), started
     * before any test method executes, and stopped after all tests complete.</p>
     *
     * <p>Database name {@code jpetstore_catalog_test} reflects the Catalog Service
     * bounded context and avoids conflicts with other service test databases.</p>
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("jpetstore_catalog_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Wires the Testcontainers PostgreSQL connection properties into the Spring
     * Boot application context at runtime, overriding the production datasource
     * configuration from {@code application.yml}.
     *
     * <p>Property overrides:</p>
     * <ul>
     *   <li>{@code spring.datasource.url} — Testcontainers-assigned JDBC URL
     *       (random host port mapped to container's 5432)</li>
     *   <li>{@code spring.datasource.username} / {@code password} — Container
     *       credentials ({@code "test"}/{@code "test"})</li>
     *   <li>{@code spring.jpa.hibernate.ddl-auto=create-drop} — Hibernate creates
     *       the schema from entity annotations on startup and drops it on shutdown.
     *       This validates that all entity mappings (column names, types, FK
     *       relationships, indexes) are correct. Repository ITs use Liquibase
     *       changelogs instead.</li>
     *   <li>{@code spring.liquibase.enabled=false} — Disables Liquibase schema
     *       management since Hibernate DDL handles schema creation for this test.
     *       Prevents conflict between Liquibase changelogs and Hibernate's
     *       {@code create-drop} strategy.</li>
     * </ul>
     *
     * @param registry Spring dynamic property registry for runtime property injection
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");
    }

    /**
     * The Spring application context injected by the {@code @SpringBootTest}
     * framework. This is the primary assertion subject — if this field fails
     * to be injected, the application context did not load successfully.
     *
     * <p>Provides {@code getBean(Class)} method access to retrieve and verify
     * individual Spring bean instances (controllers, services, repositories)
     * in all four test methods.</p>
     */
    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Verifies that the Spring Boot application context loads without errors.
     *
     * <p>This is the simplest and most fundamental test — if ANY bean fails to
     * create (e.g., missing repository, broken entity mapping, invalid
     * configuration), this test fails with a clear Spring error message
     * identifying the root cause.</p>
     *
     * <p>A passing result confirms that:</p>
     * <ul>
     *   <li>{@code CatalogServiceApplication} {@code @SpringBootApplication}
     *       successfully bootstraps</li>
     *   <li>Component scanning discovers all beans in {@code com.jpetstore.catalog}</li>
     *   <li>Spring Boot auto-configuration (JPA, Security, Actuator) completes</li>
     *   <li>PostgreSQL datasource connection is established via Testcontainers</li>
     *   <li>Hibernate schema generation from entity annotations succeeds</li>
     * </ul>
     */
    @Test
    void contextLoads() {
        assertThat(applicationContext).isNotNull();
    }

    /**
     * Verifies that both {@code @Service} beans in the Catalog Service are
     * present in the application context with their dependencies correctly
     * injected.
     *
     * <p>Beans verified:</p>
     * <ul>
     *   <li>{@link CatalogService} — Read operations for categories, products,
     *       and items. Requires 4 repository dependencies:
     *       {@code CategoryRepository}, {@code ProductRepository},
     *       {@code ItemRepository}, {@code InventoryRepository}</li>
     *   <li>{@link InventoryService} — Atomic inventory management with
     *       optimistic locking. Requires {@code InventoryRepository} and
     *       {@code InventoryReservationRepository} dependencies</li>
     * </ul>
     *
     * <p>Catches missing {@code @Service} annotations, unresolvable constructor
     * injection dependencies, and circular dependency issues in the service
     * layer.</p>
     */
    @Test
    void allServiceBeansLoaded() {
        assertThat(applicationContext.getBean(CatalogService.class)).isNotNull();
        assertThat(applicationContext.getBean(InventoryService.class)).isNotNull();
    }

    /**
     * Verifies that all three {@code @RestController} beans in the Catalog
     * Service are present in the application context with their service
     * dependencies correctly injected.
     *
     * <p>Beans verified:</p>
     * <ul>
     *   <li>{@link CategoryController} — REST endpoints for category browsing
     *       ({@code GET /api/categories}, {@code GET /api/categories/{id}}).
     *       Requires {@code CatalogService} dependency.</li>
     *   <li>{@link ProductController} — REST endpoints for product browsing and
     *       search ({@code GET /api/products}, {@code GET /api/products/{id}},
     *       {@code GET /api/products/search}). Requires {@code CatalogService}
     *       dependency.</li>
     *   <li>{@link ItemController} — REST endpoints for item browsing and
     *       inventory management ({@code GET /api/items}, {@code GET /api/items/{id}},
     *       {@code POST /api/items/{id}/inventory/decrement}). Requires both
     *       {@code CatalogService} and {@code InventoryService} dependencies.</li>
     * </ul>
     *
     * <p>Catches missing {@code @RestController} annotations, unresolved service
     * dependencies, and Spring MVC configuration issues.</p>
     */
    @Test
    void allControllerBeansLoaded() {
        assertThat(applicationContext.getBean(CategoryController.class)).isNotNull();
        assertThat(applicationContext.getBean(ProductController.class)).isNotNull();
        assertThat(applicationContext.getBean(ItemController.class)).isNotNull();
    }

    /**
     * Verifies that all five Spring Data JPA repository beans in the Catalog
     * Service are present in the application context, confirming that JPA entity
     * mappings and repository auto-detection are correctly configured.
     *
     * <p>Beans verified:</p>
     * <ul>
     *   <li>{@link CategoryRepository} — {@code JpaRepository<Category, String>},
     *       validates Category entity's {@code @Entity} mapping</li>
     *   <li>{@link ProductRepository} — {@code JpaRepository<Product, String>},
     *       validates Product entity's {@code @ManyToOne} relationship to
     *       Category and custom {@code @Query} for keyword search</li>
     *   <li>{@link ItemRepository} — {@code JpaRepository<Item, String>},
     *       validates Item entity's {@code @ManyToOne} relationships to Product
     *       and Supplier, {@code @EntityGraph} configuration</li>
     *   <li>{@link InventoryRepository} — {@code JpaRepository<Inventory, String>},
     *       validates Inventory entity's {@code @Version} for optimistic locking
     *       and custom {@code @Modifying @Query} for atomic decrement</li>
     *   <li>{@link SupplierRepository} — {@code JpaRepository<Supplier, Integer>},
     *       validates Supplier entity's {@code @Entity} mapping</li>
     * </ul>
     *
     * <p>This test is particularly valuable because JPA entity validation occurs
     * at context startup: invalid entity mappings (broken {@code @Entity},
     * wrong column names, bad FK constraints) cause the EntityManagerFactory
     * creation to fail, which prevents repository bean instantiation.</p>
     */
    @Test
    void allRepositoryBeansLoaded() {
        assertThat(applicationContext.getBean(CategoryRepository.class)).isNotNull();
        assertThat(applicationContext.getBean(ProductRepository.class)).isNotNull();
        assertThat(applicationContext.getBean(ItemRepository.class)).isNotNull();
        assertThat(applicationContext.getBean(InventoryRepository.class)).isNotNull();
        assertThat(applicationContext.getBean(SupplierRepository.class)).isNotNull();
    }
}
