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
# Catalog Service Dockerfile — Multi-stage Spring Boot 3 Build
# =============================================================================
# Service : catalog-service (Catalog/Inventory bounded context)
# Java    : 17 (required by Spring Boot 3.5.x)
# Port    : 8082 (per AAP routing table — Catalog Service)
#
# Build context : Repository root (.) — set via docker-compose.yml
#   docker-compose.yml specifies:
#     build:
#       context: .
#       dockerfile: catalog-service/Dockerfile
#
# This ensures the parent POM and all module sources are accessible during
# the Docker build. The catalog-service module is built using the multi-module
# Maven project structure with -pl catalog-service -am flags.
#
# Bounded Context:
#   Owns: category, product, item, inventory, supplier tables
#   Provides: Read-only catalog browsing, product search, item details,
#             and atomic inventory decrement with optimistic locking
#   Replaces: CatalogService.java and Category/Product/Item Mappers from monolith
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: BUILD — Compile the Catalog Service application
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy the parent POM and ALL child module POMs first for Docker layer caching
# of dependencies. This layer is only rebuilt when POM files change, saving
# significant build time on iterative source code changes.
#
# CRITICAL: The parent POM declares all 6 child modules (<modules> section).
# Maven's reactor resolution requires every declared child module POM to exist,
# even when building a single module with -pl. Without these POMs, the build
# fails with "Child module does not exist" errors.
COPY pom.xml ./pom.xml
COPY catalog-service/pom.xml ./catalog-service/pom.xml
COPY monolith/pom.xml ./monolith/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml
COPY account-service/pom.xml ./account-service/pom.xml
COPY order-service/pom.xml ./order-service/pom.xml
COPY migration/pom.xml ./migration/pom.xml

# Download all dependencies into the local Maven cache (cached layer).
# Uses -pl catalog-service -am to resolve the parent POM and catalog-service deps.
# The '|| true' allows the build to continue even if some dependencies cannot be
# fully resolved offline (e.g., parent POM plugins); the package step will
# download any remaining artifacts.
RUN mvn dependency:go-offline -B -pl catalog-service -am || true

# Copy catalog-service application source code
COPY catalog-service/src/ ./catalog-service/src/

# Build the executable Spring Boot JAR.
# -pl catalog-service : Build only the catalog-service module
# -am                 : Also build any modules that catalog-service depends on (parent)
# Tests are skipped here — they are executed separately in the CI pipeline.
RUN mvn clean package -DskipTests -B -pl catalog-service -am \
    && mv catalog-service/target/*-exec.jar catalog-service/target/app.jar

# -----------------------------------------------------------------------------
# Stage 2: RUNTIME — Minimal JRE image for production deployment
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

# Image metadata labels
LABEL maintainer="jpetstore-team" \
      service="catalog-service" \
      description="JPetStore Catalog Service — Catalog/Inventory bounded context microservice" \
      org.opencontainers.image.source="https://github.com/mybatis/jpetstore-6" \
      org.opencontainers.image.title="JPetStore Catalog Service" \
      org.opencontainers.image.description="Spring Boot 3 microservice managing the Catalog/Inventory bounded context — categories, products, items, inventory, and suppliers with atomic inventory decrement and optimistic locking"

# Install curl for health checks and clean up apt cache to keep image small.
# Create a dedicated non-root user and group for running the application.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 appgroup \
    && useradd --system --uid 1001 --gid appgroup --shell /bin/false appuser

WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/catalog-service/target/app.jar app.jar

# Set file ownership to the non-root application user
RUN chown appuser:appgroup app.jar

# Run as non-root user for security
USER appuser

# Expose the Catalog Service port.
# This service provides REST APIs for catalog browsing, product search,
# item details, and inventory management. It is accessed by:
#   - API Gateway for proxied client requests
#   - Account Service for personalization (getProductListByCategory)
#   - Order Service for inventory reservation during order placement (Saga)
EXPOSE 8082

# Health check using Spring Boot Actuator endpoint.
# - interval  : Check every 30 seconds
# - timeout   : Allow 10 seconds for the health endpoint to respond
# - start     : Wait 40 seconds for application startup before first check
# - retries   : Mark unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -sf http://localhost:8082/actuator/health || exit 1

# JVM entry point with container-aware flags:
# - UseContainerSupport : Respect container CPU/memory limits (default on JDK 17+)
# - MaxRAMPercentage    : Use up to 75% of container memory for the JVM heap
# - java.security.egd   : Use non-blocking entropy source to avoid startup delays
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
