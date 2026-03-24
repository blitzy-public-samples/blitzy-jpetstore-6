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

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main entry point for the JPetStore API Gateway service.
 *
 * <p>This Spring Boot application serves as the single public entry point for
 * all client requests, implementing the Strangler Fig pattern to progressively
 * route traffic from the legacy monolith to the new microservices (Account,
 * Catalog, and Order services).</p>
 *
 * <p>The gateway runs on the reactive Netty stack via Spring Cloud Gateway and
 * provides the following capabilities:</p>
 * <ul>
 *   <li>Runtime-switchable per-service routing via Redis-backed flags</li>
 *   <li>JWT-based authentication enforcement for protected endpoints</li>
 *   <li>Static route definitions mapping URL patterns to backend services</li>
 *   <li>Health check and metrics via Spring Boot Actuator</li>
 * </ul>
 *
 * <p>Note: Service discovery is not used. All backend service URLs are
 * configured statically in {@code application.yml}.</p>
 *
 * @see org.springframework.cloud.gateway.route.RouteLocator
 */
@SpringBootApplication
public class GatewayApplication {

    /**
     * Application entry point. Bootstraps the Spring Cloud Gateway
     * application context and starts the embedded Netty server.
     *
     * @param args command-line arguments passed to the Spring Boot application
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
