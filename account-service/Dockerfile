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

# ==============================================================================
# Account Service — Multi-Stage Docker Build
# ==============================================================================
# Produces an optimized, minimal Docker image for the Account Service
# Spring Boot 3 microservice (Account/User Management bounded context).
#
# Build context: repository root (set by docker-compose.yml: context: .)
# This ensures the parent POM and all module sources are accessible during
# the Docker build. The account-service module is built using the multi-module
# Maven project structure with -pl account-service -am flags.
#
#   docker-compose build account-service
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build — Compile the Spring Boot executable JAR
# ------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy the parent POM and account-service POM first for Docker layer caching of
# dependencies. This layer is only rebuilt when POM files change, saving
# significant build time on iterative source code changes.
COPY pom.xml ./pom.xml
COPY account-service/pom.xml ./account-service/pom.xml

# Pre-fetch dependencies into the local Maven repository (caching layer).
# Uses -pl account-service -am to resolve the parent POM and account-service deps.
# The '|| true' ensures the build continues even if some dependencies cannot be
# fully resolved offline; the package step will download any remaining artifacts.
RUN mvn dependency:go-offline -B -pl account-service -am || true

# Copy account-service application source code
COPY account-service/src/ ./account-service/src/

# Build the executable Spring Boot JAR, skipping tests for faster image builds.
# -pl account-service : Build only the account-service module
# -am                 : Also build any modules that account-service depends on (parent)
# Tests are expected to run in CI before the Docker build step.
RUN mvn clean package -B -DskipTests -pl account-service -am \
    && mv account-service/target/*.jar account-service/target/app.jar

# ------------------------------------------------------------------------------
# Stage 2: Runtime — Minimal JRE image for production execution
# ------------------------------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

# Metadata labels for container identification and management
LABEL maintainer="JPetStore Team" \
      service.name="account-service" \
      service.version="6.2.1-SNAPSHOT" \
      service.description="Account/User Management microservice for JPetStore" \
      service.port="8081"

WORKDIR /app

# Install curl for HTTP-based health checks against the Spring Boot Actuator
# endpoint; clean up apt cache to keep the image size minimal.
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Create a dedicated non-root user for running the application.
# Running containers as non-root is a security best practice that limits
# the blast radius of any container escape vulnerability.
RUN groupadd --system appuser && \
    useradd --system --gid appuser --no-create-home appuser

# Copy the executable JAR from the build stage
COPY --from=build /app/account-service/target/app.jar app.jar

# Set ownership to the non-root user
RUN chown appuser:appuser app.jar

# Switch to the non-root user for all subsequent commands and runtime
USER appuser

# Account Service listens on port 8081 per the microservices architecture.
# This is configured in application.yml (server.port=8081).
EXPOSE 8081

# Health check using Spring Boot Actuator's health endpoint.
# - interval:      Check every 30 seconds
# - timeout:       Fail if no response within 3 seconds
# - start-period:  Allow 60 seconds for Spring Boot startup before counting failures
# - retries:       Mark unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1

# JVM configuration and application entry point.
# -Djava.security.egd: Use non-blocking entropy source for faster startup
# -Xmx512m:           Maximum heap size (suitable for this service's workload)
# -Xms256m:           Initial heap size (reduces GC overhead at startup)
# -XX:+UseG1GC:       G1 garbage collector (optimized for low-latency services)
ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Xmx512m", \
    "-Xms256m", \
    "-XX:+UseG1GC", \
    "-jar", \
    "app.jar"]
