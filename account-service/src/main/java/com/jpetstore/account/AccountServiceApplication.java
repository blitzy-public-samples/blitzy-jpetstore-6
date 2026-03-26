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
package com.jpetstore.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot 3 entry point for the Account Service microservice.
 *
 * <p>This class bootstraps the Account Service, which owns the Account/User Management
 * bounded context extracted from the JPetStore monolith. It manages the {@code account},
 * {@code profile}, {@code signon}, and {@code bannerdata} tables in a dedicated
 * PostgreSQL database.</p>
 *
 * <p>The {@link SpringBootApplication} annotation enables:</p>
 * <ul>
 *   <li>{@code @EnableAutoConfiguration} — auto-configures Spring Data JPA (PostgreSQL),
 *       Spring Security (JWT-based stateless authentication), Liquibase (schema management),
 *       Spring Data Redis, and Spring MVC (REST API)</li>
 *   <li>{@code @ComponentScan} — scans the {@code com.jpetstore.account} package tree
 *       including controller, service, entity, repository, dto, security, and config
 *       sub-packages</li>
 *   <li>{@code @Configuration} — marks this class as a Spring configuration source</li>
 * </ul>
 *
 * <p>This class replaces the monolith's XML-based Spring context bootstrapping
 * ({@code applicationContext.xml} + {@code web.xml} + {@code ContextLoaderListener} +
 * {@code StripesFilter} chain) with annotation-driven auto-configuration for the
 * Account bounded context.</p>
 *
 * @author JPetStore Microservices Team
 * @since 1.0.0
 */
@SpringBootApplication
public class AccountServiceApplication {

    /**
     * Application entry point that launches the Account Service.
     *
     * <p>Delegates to {@link SpringApplication#run(Class, String...)} to create
     * the Spring application context, trigger auto-configuration for all registered
     * starters (Web, Data JPA, Security, Actuator, Redis, Liquibase), and start the
     * embedded Tomcat server.</p>
     *
     * @param args command-line arguments passed to the Spring Boot application;
     *             supports standard Spring Boot externalized configuration
     *             (e.g., {@code --server.port=8081}, {@code --spring.profiles.active=prod})
     */
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }

}
