#
#    Copyright 2010-2026 the original author or authors.
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#       https://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.
#

# =============================================================================
# Order Service Dockerfile — Multi-stage Spring Boot 3 Build
# =============================================================================
# Service : order-service (Order/Cart bounded context)
# Java    : 17 (required by Spring Boot 3.5.x)
# Port    : 8083 (per AAP routing table — Order Service)
#
# Build context : Repository root (.) — set via docker-compose.yml
#   docker-compose.yml specifies:
#     build:
#       context: .
#       dockerfile: order-service/Dockerfile
#
# This ensures the parent POM and all module sources are accessible during
# the Docker build. The order-service module is built using the multi-module
# Maven project structure with -pl order-service -am flags.
#
# Bounded Context:
#   Owns: orders, orderstatus, lineitem tables + PostgreSQL sequences
#   Provides: Order placement with Saga orchestration, order history,
#             externalized cart state management via Redis
#   Consumes: Account Service (user verification), Catalog Service (inventory
#             reservation/decrement during distributed order transaction)
#   Replaces: OrderService.java and Order/LineItem/Sequence Mappers from monolith
#
# This is the highest-risk microservice due to:
#   - Distributed Saga transaction spanning Order and Catalog bounded contexts
#   - Redis-backed externalized cart state (replacing session-scoped Cart)
#   - Cross-service REST clients to Account Service and Catalog Service
#   - PostgreSQL sequence-based ID generation replacing legacy sequence table
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: BUILD — Compile the Order Service application
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy the parent POM and order-service POM first for Docker layer caching of
# dependencies. This layer is only rebuilt when POM files change, saving
# significant build time on iterative source code changes.
COPY pom.xml ./pom.xml
COPY order-service/pom.xml ./order-service/pom.xml

# Download all dependencies into the local Maven cache (cached layer).
# Uses -pl order-service -am to resolve the parent POM and order-service deps.
# The '|| true' allows the build to continue even if some dependencies cannot be
# fully resolved offline (e.g., parent POM plugins); the package step will
# download any remaining artifacts.
RUN mvn dependency:go-offline -B -pl order-service -am || true

# Copy order-service application source code
COPY order-service/src/ ./order-service/src/

# Build the executable Spring Boot JAR.
# -pl order-service : Build only the order-service module
# -am               : Also build any modules that order-service depends on (parent)
# Tests are skipped here — they are executed separately in the CI pipeline.
RUN mvn clean package -DskipTests -B -pl order-service -am \
    && mv order-service/target/*.jar order-service/target/app.jar

# -----------------------------------------------------------------------------
# Stage 2: RUNTIME — Minimal JRE image for production deployment
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

# Image metadata labels
LABEL maintainer="jpetstore-team" \
      service="order-service" \
      description="JPetStore Order Service — Order/Cart bounded context microservice with Saga orchestration" \
      org.opencontainers.image.source="https://github.com/mybatis/jpetstore-6" \
      org.opencontainers.image.title="JPetStore Order Service" \
      org.opencontainers.image.description="Spring Boot 3 microservice managing the Order/Cart bounded context — order placement via Saga pattern, order history, externalized cart state via Redis, and cross-service coordination with Account and Catalog services for distributed order transactions"

# Install curl for health checks and clean up apt cache to keep image small.
# Create a dedicated non-root user and group for running the application.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 appgroup \
    && useradd --system --uid 1001 --gid appgroup --shell /bin/false appuser

WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/order-service/target/app.jar app.jar

# Set file ownership to the non-root application user
RUN chown appuser:appgroup app.jar

# Run as non-root user for security
USER appuser

# Expose the Order Service port.
# This service provides REST APIs for:
#   - Order placement (POST /api/orders) with Saga orchestration
#   - Order history (GET /api/orders?username=, GET /api/orders/{id})
#   - Cart state management (GET/POST/PUT/DELETE /api/cart/{sessionId})
# It is accessed by:
#   - API Gateway for proxied client requests (Cart.action and Order.action)
#   - Monolith ActionBeans during Strangler Fig coexistence window
EXPOSE 8083

# Health check using Spring Boot Actuator endpoint.
# - interval  : Check every 30 seconds
# - timeout   : Allow 10 seconds for the health endpoint to respond
# - start     : Wait 40 seconds for application startup before first check
# - retries   : Mark unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -sf http://localhost:8083/actuator/health || exit 1

# JVM entry point with container-aware flags:
# - UseContainerSupport : Respect container CPU/memory limits (default on JDK 17+)
# - MaxRAMPercentage    : Use up to 75% of container memory for the JVM heap
# - java.security.egd   : Use non-blocking entropy source to avoid startup delays
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
