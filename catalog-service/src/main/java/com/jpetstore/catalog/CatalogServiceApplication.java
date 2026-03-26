package com.jpetstore.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 3 main application class for the Catalog Service microservice.
 *
 * <p>This class serves as the entry point for the Catalog/Inventory bounded context,
 * which is the first service to be cut over in the Strangler Fig migration pattern
 * from the JPetStore 6 monolith. It replaces the monolith's XML-based Spring context
 * ({@code applicationContext.xml}) for the catalog bounded context, transitioning from
 * embedded HSQLDB with MyBatis mapper XML definitions to Spring Boot auto-configuration
 * with PostgreSQL and Spring Data JPA.</p>
 *
 * <p>The {@link SpringBootApplication @SpringBootApplication} composite annotation enables:</p>
 * <ul>
 *   <li><strong>Auto-configuration</strong> — automatically configures Spring Data JPA,
 *       PostgreSQL datasource, Liquibase schema management, embedded Tomcat, and
 *       Actuator health endpoints based on classpath dependencies</li>
 *   <li><strong>Component scanning</strong> — discovers all Spring-managed beans in
 *       {@code com.jpetstore.catalog} and all sub-packages including:
 *       <ul>
 *         <li>{@code com.jpetstore.catalog.controller} — REST API controllers
 *             (CategoryController, ProductController, ItemController)</li>
 *         <li>{@code com.jpetstore.catalog.service} — Business logic services
 *             (CatalogService, InventoryService)</li>
 *         <li>{@code com.jpetstore.catalog.repository} — Spring Data JPA repositories
 *             (CategoryRepository, ProductRepository, ItemRepository,
 *              InventoryRepository, SupplierRepository)</li>
 *         <li>{@code com.jpetstore.catalog.entity} — JPA entity classes
 *             (Category, Product, Item, Inventory, Supplier)</li>
 *         <li>{@code com.jpetstore.catalog.dto} — Data transfer objects
 *             (CategoryDTO, ProductDTO, ItemDTO, InventoryDecrementRequest)</li>
 *         <li>{@code com.jpetstore.catalog.config} — Configuration classes
 *             (AppConfig, DualWriteConfig)</li>
 *       </ul>
 *   </li>
 *   <li><strong>Configuration properties</strong> — binds externalized configuration
 *       from {@code application.yml} including PostgreSQL datasource settings,
 *       JPA/Hibernate properties, Liquibase changelog paths, and Actuator endpoints</li>
 * </ul>
 *
 * <p><strong>Owned database tables</strong> (Catalog/Inventory bounded context):</p>
 * <ul>
 *   <li>{@code category} — Pet categories (FISH, DOGS, REPTILES, CATS, BIRDS)</li>
 *   <li>{@code product} — Products within categories</li>
 *   <li>{@code item} — Individual purchasable items with pricing and attributes</li>
 *   <li>{@code inventory} — Stock quantities per item (with optimistic locking)</li>
 *   <li>{@code supplier} — Supplier information</li>
 * </ul>
 *
 * <p><strong>Design notes</strong>:</p>
 * <ul>
 *   <li>No {@code @EnableJpaRepositories} annotation is needed — Spring Boot
 *       auto-detects Spring Data JPA repositories in scanned packages</li>
 *   <li>No {@code @EnableDiscoveryClient} — static routing via API Gateway,
 *       not service discovery</li>
 *   <li>No MyBatis, HSQLDB, or Stripes framework references — this service
 *       uses exclusively Jakarta EE 10 namespace (Spring Boot 3.x)</li>
 *   <li>Uses PostgreSQL as the sole database backend per the database-per-service
 *       pattern; HSQLDB is prohibited in new services</li>
 * </ul>
 *
 * @see org.springframework.boot.autoconfigure.SpringBootApplication
 * @see org.springframework.boot.SpringApplication
 */
@SpringBootApplication
public class CatalogServiceApplication {

    /**
     * Main entry point for the Catalog Service microservice.
     *
     * <p>Bootstraps the Spring Boot application context, starts the embedded
     * Tomcat server, initializes the PostgreSQL datasource connection pool,
     * runs Liquibase schema migrations, and begins accepting REST API requests
     * on the configured server port (default: 8082).</p>
     *
     * @param args command-line arguments passed to the Spring Boot application;
     *             supports standard Spring Boot externalized configuration overrides
     *             (e.g., {@code --server.port=8082}, {@code --spring.profiles.active=prod})
     */
    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
