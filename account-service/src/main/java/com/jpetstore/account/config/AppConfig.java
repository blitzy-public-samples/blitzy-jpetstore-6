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
package com.jpetstore.account.config;

import java.time.Duration;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Spring Boot 3 application configuration for the Account Service microservice.
 *
 * <p>This class replaces the monolith's XML-based {@code applicationContext.xml} with
 * annotation-driven Java configuration. The monolith's XML context configured an embedded
 * HSQLDB DataSource, DataSourceTransactionManager, MyBatis SqlSessionFactory, component
 * scanning, and annotation-driven transaction demarcation. In the Account Service, all of
 * those responsibilities are handled by Spring Boot 3 auto-configuration:</p>
 *
 * <ul>
 *   <li>Embedded HSQLDB DataSource → PostgreSQL DataSource auto-configured from
 *       {@code spring.datasource.*} properties in {@code application.yml}</li>
 *   <li>DataSourceTransactionManager → JpaTransactionManager auto-configured by
 *       {@code spring-boot-starter-data-jpa}</li>
 *   <li>Component scanning → Handled by {@code @SpringBootApplication} on
 *       {@code AccountServiceApplication}</li>
 *   <li>Annotation-driven transactions → Auto-enabled by
 *       {@code spring-boot-starter-data-jpa}</li>
 *   <li>MyBatis SqlSessionFactory + mapper scanning → Replaced entirely by Spring Data
 *       JPA repositories, auto-scanned by Spring Boot</li>
 * </ul>
 *
 * <p>This configuration class provides only the beans that Spring Boot auto-configuration
 * does not cover — specifically, a pre-configured {@link RestTemplate} for inter-service
 * REST communication. The Account Service has an outbound dependency on the Catalog Service
 * for personalization ({@code GET /api/products?categoryId=} to populate the user's
 * favorite products list), as documented in AAP Section 0.7.4.</p>
 *
 * <p><strong>HSQLDB Prohibition:</strong> This class must NOT configure or reference HSQLDB
 * in any way. All database access is through the auto-configured PostgreSQL DataSource.
 * HSQLDB access is handled exclusively by {@code DualWriteConfig} when dual-write is
 * enabled during the coexistence window.</p>
 *
 * @see org.springframework.boot.autoconfigure.SpringBootApplication
 * @see org.springframework.boot.web.client.RestTemplateBuilder
 */
@Configuration
public class AppConfig {

    /**
     * Creates a pre-configured {@link RestTemplate} bean for synchronous inter-service
     * REST communication within the JPetStore microservices ecosystem.
     *
     * <p>This bean is primarily used by the Account Service's business layer to call the
     * Catalog Service for personalization data. Specifically, the monolith's
     * {@code CatalogService.getProductListByCategory(categoryId)} call — used by
     * {@code AccountActionBean} at lines 118, 140, and 170 to populate the user's
     * {@code myList} — is replaced by a REST call to
     * {@code GET /api/products?categoryId={categoryId}} on the Catalog Service.</p>
     *
     * <p>The RestTemplate is configured with connection and read timeouts of 5 seconds
     * each to ensure graceful degradation when the Catalog Service is unavailable. If the
     * Catalog Service is slow or unreachable, the timeout prevents the Account Service from
     * blocking indefinitely. The calling service layer should catch timeout exceptions and
     * fall back to an empty product list, preserving the user experience.</p>
     *
     * <p><strong>Timeout Configuration Rationale (AAP Section 0.7.4):</strong></p>
     * <ul>
     *   <li>Connection timeout (5s): Maximum time to establish a TCP connection to the
     *       Catalog Service. Exceeding this indicates the service is unreachable.</li>
     *   <li>Read timeout (5s): Maximum time to wait for a response after the connection
     *       is established. Exceeding this indicates the service is overloaded or hung.</li>
     * </ul>
     *
     * @param builder the Spring Boot {@link RestTemplateBuilder} auto-configured with
     *                sensible defaults, injected by the Spring container
     * @return a fully configured {@link RestTemplate} instance ready for inter-service
     *         HTTP communication with appropriate timeout settings
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();
    }
}
