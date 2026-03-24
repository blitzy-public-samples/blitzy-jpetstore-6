package com.jpetstore.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Primary Spring configuration class for the Catalog Service microservice.
 *
 * <p>This configuration replaces the monolith's XML-based {@code applicationContext.xml}
 * for the Catalog/Inventory bounded context. The monolith's configuration defined the
 * following beans via Spring XML:</p>
 *
 * <ol>
 *   <li><strong>Embedded HSQLDB DataSource</strong> ({@code <jdbc:embedded-database>}) with
 *       schema and data-load scripts &mdash; <em>replaced by</em>: PostgreSQL datasource
 *       auto-configured via {@code spring.datasource.*} properties in {@code application.yml},
 *       with schema managed by Liquibase changelogs</li>
 *   <li><strong>DataSourceTransactionManager</strong> &mdash; <em>replaced by</em>:
 *       {@code JpaTransactionManager} auto-configured by Spring Boot when
 *       {@code spring-boot-starter-data-jpa} is on the classpath</li>
 *   <li><strong>Component scan</strong> of {@code org.mybatis.jpetstore.service} &mdash;
 *       <em>replaced by</em>: {@code @SpringBootApplication} auto-scan of
 *       {@code com.jpetstore.catalog} and all sub-packages</li>
 *   <li><strong>Transaction annotation-driven</strong> ({@code <tx:annotation-driven/>}) &mdash;
 *       <em>replaced by</em>: automatically enabled by Spring Boot JPA starter</li>
 *   <li><strong>MyBatis SqlSessionFactory</strong> with domain type aliases &mdash;
 *       <em>replaced by</em>: JPA {@code EntityManagerFactory} auto-configured by Hibernate</li>
 *   <li><strong>MyBatis mapper scan</strong> of {@code org.mybatis.jpetstore.mapper} &mdash;
 *       <em>replaced by</em>: Spring Data JPA auto-detected repositories
 *       ({@code CategoryRepository}, {@code ProductRepository}, {@code ItemRepository},
 *       {@code InventoryRepository}, {@code SupplierRepository})</li>
 * </ol>
 *
 * <p><strong>Why this class is intentionally minimal:</strong> Spring Boot 3 auto-configuration
 * handles virtually everything the monolith's XML config did, but using PostgreSQL + Spring Data JPA
 * instead of HSQLDB + MyBatis. This class defines <em>only</em> custom beans that Spring Boot
 * does not auto-configure out of the box.</p>
 *
 * <p><strong>Beans NOT defined here</strong> (handled by Spring Boot auto-configuration):</p>
 * <ul>
 *   <li>{@code DataSource} &mdash; auto-configured from {@code spring.datasource.*} properties</li>
 *   <li>{@code EntityManagerFactory} &mdash; auto-configured from {@code spring.jpa.*} properties</li>
 *   <li>{@code JpaTransactionManager} &mdash; auto-configured when JPA starter is present</li>
 *   <li>{@code ObjectMapper} &mdash; auto-configured by Spring Boot with sensible Jackson defaults</li>
 * </ul>
 *
 * <p><strong>Annotations NOT needed here</strong> (auto-enabled by Spring Boot starters):</p>
 * <ul>
 *   <li>{@code @EnableJpaRepositories} &mdash; Spring Boot auto-detects JPA repositories</li>
 *   <li>{@code @EnableTransactionManagement} &mdash; auto-enabled by data-jpa starter</li>
 *   <li>{@code @ComponentScan} &mdash; {@code @SpringBootApplication} on
 *       {@code CatalogServiceApplication} already scans all sub-packages</li>
 * </ul>
 *
 * <p><strong>Technology stack</strong>: Java 17, Spring Boot 3.5.x, PostgreSQL,
 * Spring Data JPA, Liquibase. No MyBatis, HSQLDB, or javax.* namespace references &mdash;
 * this service uses exclusively the Jakarta EE 10 namespace (Spring Boot 3.x).</p>
 *
 * @see com.jpetstore.catalog.CatalogServiceApplication
 */
@Configuration
public class AppConfig {

    /**
     * Creates a {@link RestClient} bean for potential inter-service REST communication.
     *
     * <p>{@code RestClient} is the modern HTTP client introduced in Spring Framework 6.1
     * (Spring Boot 3.2+), preferred over the legacy {@code RestTemplate} for synchronous
     * HTTP calls in Spring Boot 3.x applications. It provides a fluent, functional-style
     * API for building and executing HTTP requests.</p>
     *
     * <p>The Catalog Service is primarily <em>called by</em> other services (Order Service
     * for inventory operations, Account Service for personalization, API Gateway for routing),
     * but this bean is available for injection into any component that may need to make
     * outbound REST calls to other microservices in the decomposed architecture.</p>
     *
     * <p>The default configuration is used, which includes:</p>
     * <ul>
     *   <li>Standard HTTP timeouts managed by the underlying HTTP client</li>
     *   <li>Default error handling for non-2xx status codes</li>
     *   <li>Jackson-based message conversion for JSON request/response bodies</li>
     * </ul>
     *
     * @return a configured {@link RestClient} instance managed by the Spring IoC container
     */
    @Bean
    public RestClient restClient() {
        return RestClient.builder().build();
    }
}
