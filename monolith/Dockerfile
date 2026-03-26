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
# JPetStore Monolith Dockerfile — Multi-Stage Build with Tomcat 9 Runtime
# =============================================================================
# Service : monolith (Original JPetStore WAR — Stripes MVC + MyBatis + HSQLDB)
# Java    : 17 (required by project build configuration)
# Port    : 8080 (mapped to 8090 externally by docker-compose.yml)
#
# Build context : Repository root (.) — set via docker-compose.yml
#   docker-compose.yml specifies:
#     build:
#       context: .
#       dockerfile: monolith/Dockerfile
#
# This ensures the parent POM, Maven Wrapper (mvnw, .mvn/), and all module
# sources are accessible during the Docker build. The monolith module is built
# using the multi-module Maven project structure with -pl monolith -am flags.
#
# Multi-stage build:
#   Stage 1 (build): Compiles the monolith WAR using Maven
#   Stage 2 (runtime): Deploys WAR to Tomcat 9 for a minimal production image
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: BUILD — Compile the monolith WAR
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
COPY monolith/pom.xml ./monolith/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml
COPY account-service/pom.xml ./account-service/pom.xml
COPY catalog-service/pom.xml ./catalog-service/pom.xml
COPY order-service/pom.xml ./order-service/pom.xml
COPY migration/pom.xml ./migration/pom.xml

# Download all dependencies into the local Maven cache (cached layer).
# Uses -pl monolith -am to resolve the parent POM and monolith deps.
RUN mvn dependency:go-offline -B -pl monolith -am || true

# Copy monolith application source code and web resources
COPY monolith/src/ ./monolith/src/

# Build the monolith WAR file.
# -pl monolith : Build only the monolith module
# -am          : Also build any modules that monolith depends on (parent POM)
# Tests are skipped here — they are executed separately in the CI pipeline.
RUN mvn clean package -B -DskipTests -pl monolith -am

# -----------------------------------------------------------------------------
# Stage 2: RUNTIME — Tomcat 9 with JRE 17 for production deployment
# -----------------------------------------------------------------------------
FROM tomcat:9.0-jre17-temurin AS runtime

# Image metadata labels
LABEL maintainer="jpetstore-team" \
      service="monolith" \
      description="JPetStore Monolith — Original Stripes MVC + MyBatis + HSQLDB WAR on Tomcat 9" \
      org.opencontainers.image.source="https://github.com/mybatis/jpetstore-6" \
      org.opencontainers.image.title="JPetStore Monolith" \
      org.opencontainers.image.description="Preserved JPetStore 6 monolith WAR deployed on Tomcat 9 during Strangler Fig coexistence window"

# Install curl for health checks and clean up apt cache to keep image small.
# Create a dedicated non-root user and group for running the application.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 appgroup \
    && useradd --system --uid 1001 --gid appgroup --shell /bin/false appuser

# Remove default Tomcat webapps to keep the image clean
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the built WAR from the build stage as ROOT.war to deploy at root context
# path (/), matching the Cargo plugin's deployment behavior.
COPY --from=build /app/monolith/target/jpetstore.war /usr/local/tomcat/webapps/ROOT.war

# Set ownership of Tomcat directories to the non-root application user
RUN chown -R appuser:appgroup /usr/local/tomcat/

# Run as non-root user for security
USER appuser

# Expose the monolith's internal port (Tomcat 9 serves on 8080)
EXPOSE 8080

# Health check using Tomcat's HTTP endpoint.
# - interval  : Check every 30 seconds
# - timeout   : Allow 10 seconds for the health endpoint to respond
# - start     : Wait 40 seconds for application startup before first check
# - retries   : Mark unhealthy after 3 consecutive failures
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -sf http://localhost:8080/ || exit 1

# Start Tomcat using the standard catalina.sh launcher
CMD ["catalina.sh", "run"]
