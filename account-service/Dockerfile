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
# Build context: account-service/ directory
#   docker build -t account-service .
#
# Or from the repository root via docker-compose:
#   docker-compose build account-service
# ==============================================================================

# ------------------------------------------------------------------------------
# Stage 1: Build — Compile the Spring Boot executable JAR
# ------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy the Maven POM first to leverage Docker layer caching for dependencies.
# When only source code changes (not dependencies), this cached layer is reused,
# significantly speeding up subsequent builds.
COPY pom.xml .

# Pre-fetch dependencies into the local Maven repository (caching layer).
# The '|| true' ensures the build continues even if the parent POM is not yet
# available in a remote repository — the actual build step will resolve it.
RUN mvn dependency:go-offline -B 2>/dev/null || true

# Copy application source code
COPY src/ ./src/

# Build the executable Spring Boot JAR, skipping tests for faster image builds.
# Tests are expected to run in CI before the Docker build step.
RUN mvn clean package -B -DskipTests \
    && cp target/*.jar target/app.jar

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
COPY --from=build /app/target/app.jar app.jar

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
