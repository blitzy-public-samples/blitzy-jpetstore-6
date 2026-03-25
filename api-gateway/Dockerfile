#
# Copyright 2010-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# =============================================================================
# API Gateway Dockerfile — Multi-stage Spring Boot 3 Build
# =============================================================================
# Service : api-gateway (Spring Cloud Gateway with embedded Netty)
# Java    : 17 (required by Spring Boot 3.5.x)
# Port    : 8080 (single public entry point for all JPetStore services)
#
# Build context : Repository root (.) — set via docker-compose.yml
#   docker-compose.yml specifies:
#     build:
#       context: .
#       dockerfile: api-gateway/Dockerfile
#
# This ensures the parent POM and all module sources are accessible during
# the Docker build. The api-gateway module is built using the multi-module
# Maven project structure with -pl api-gateway -am flags.
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: BUILD — Compile the API Gateway application
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
COPY api-gateway/pom.xml ./api-gateway/pom.xml
COPY monolith/pom.xml ./monolith/pom.xml
COPY account-service/pom.xml ./account-service/pom.xml
COPY catalog-service/pom.xml ./catalog-service/pom.xml
COPY order-service/pom.xml ./order-service/pom.xml
COPY migration/pom.xml ./migration/pom.xml

# Download all dependencies into the local Maven cache (cached layer).
# Uses -pl api-gateway -am to resolve the parent POM and api-gateway deps.
RUN mvn dependency:go-offline -B -pl api-gateway -am || true

# Copy api-gateway application source code
COPY api-gateway/src/ ./api-gateway/src/

# Build the executable Spring Boot JAR.
# -pl api-gateway : Build only the api-gateway module
# -am             : Also build any modules that api-gateway depends on (parent)
# Tests are skipped here — they are executed separately in the CI pipeline.
RUN mvn clean package -DskipTests -B -pl api-gateway -am \
    && mv api-gateway/target/*.jar api-gateway/target/app.jar

# -----------------------------------------------------------------------------
# Stage 2: RUNTIME — Minimal JRE image for production deployment
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

# Image metadata labels
LABEL maintainer="jpetstore-team" \
      service="api-gateway" \
      description="JPetStore API Gateway — Spring Cloud Gateway with Strangler Fig routing" \
      org.opencontainers.image.source="https://github.com/mybatis/jpetstore-6" \
      org.opencontainers.image.title="JPetStore API Gateway" \
      org.opencontainers.image.description="Spring Cloud Gateway service providing unified routing, JWT authentication, and runtime-switchable Strangler Fig migration flags"

# Install curl for health checks and clean up apt cache to keep image small.
# Create a dedicated non-root user and group for running the application.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 appgroup \
    && useradd --system --uid 1001 --gid appgroup --shell /bin/false appuser

WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/api-gateway/target/app.jar app.jar

# Set file ownership to the non-root application user
RUN chown appuser:appgroup app.jar

# Run as non-root user for security
USER appuser

# Expose the API Gateway port.
# This is the single public entry point for all JPetStore traffic.
# The gateway routes requests to the monolith or individual microservices
# based on runtime-configurable per-service routing flags stored in Redis.
EXPOSE 8080

# Health check using Spring Boot Actuator endpoint.
# - interval  : Check every 30 seconds
# - timeout   : Allow 10 seconds for the health endpoint to respond
# - start     : Wait 40 seconds for application startup before first check
# - retries   : Mark unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

# JVM entry point with container-aware flags:
# - UseContainerSupport : Respect container CPU/memory limits (default on JDK 17+)
# - MaxRAMPercentage    : Use up to 75% of container memory for the JVM heap
# - java.security.egd   : Use non-blocking entropy source to avoid startup delays
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
