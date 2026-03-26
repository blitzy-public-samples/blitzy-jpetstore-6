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
# Migration Module Dockerfile — Multi-stage Build
# =============================================================================
# Module  : migration (Data Migration Tooling)
# Java    : 17 (required by parent POM)
#
# Build context : Repository root (.) — set via docker-compose.yml or CLI
#   docker build -f migration/Dockerfile .
#
# Purpose:
#   Containerizes the migration module for portable execution of HSQLDB-to-
#   PostgreSQL data migration tasks. The module contains:
#   - DataExporter.java  : HSQLDB full export with row count verification
#   - DataLoader.java    : PostgreSQL load with idempotency (UPSERT)
#   - IntegrityValidator.java : 7-check validation gate
#   - SchemaMapper.java  : HSQLDB → PostgreSQL type mapping
#   - Shell scripts      : export-hsqldb.sh, provision-postgres.sh,
#                          load-data.sh, validate-integrity.sh
#
# Usage:
#   docker run --rm --network jpetstore-network migration-tool \
#     java -cp app.jar com.jpetstore.migration.DataExporter
#   docker run --rm --network jpetstore-network migration-tool \
#     bash /app/scripts/validate-integrity.sh
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: BUILD — Compile the migration module
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy the parent POM and ALL child module POMs for Maven reactor resolution.
# The parent POM declares all 6 child modules — Maven requires every declared
# child module POM to exist even when building a single module with -pl.
COPY pom.xml ./pom.xml
COPY migration/pom.xml ./migration/pom.xml
COPY monolith/pom.xml ./monolith/pom.xml
COPY api-gateway/pom.xml ./api-gateway/pom.xml
COPY account-service/pom.xml ./account-service/pom.xml
COPY catalog-service/pom.xml ./catalog-service/pom.xml
COPY order-service/pom.xml ./order-service/pom.xml

# Download dependencies into local Maven cache (cached Docker layer)
RUN mvn dependency:go-offline -B -pl migration -am || true

# Copy migration module source code
COPY migration/src/ ./migration/src/

# Build the migration module JAR
RUN mvn clean package -DskipTests -B -pl migration -am

# -----------------------------------------------------------------------------
# Stage 2: RUNTIME — Minimal JRE image with migration tools
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre AS runtime

LABEL maintainer="jpetstore-team" \
      service="migration" \
      description="JPetStore Migration Module — HSQLDB to PostgreSQL data migration tooling" \
      org.opencontainers.image.source="https://github.com/mybatis/jpetstore-6" \
      org.opencontainers.image.title="JPetStore Migration Tool"

# Install curl for health checks and bash for shell scripts.
# Create a dedicated non-root user for running the application.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl bash postgresql-client \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 1001 appgroup \
    && useradd --system --uid 1001 --gid appgroup --shell /bin/bash appuser

WORKDIR /app

# Copy the built JAR from the build stage
COPY --from=build /app/migration/target/migration-*.jar app.jar

# Copy migration shell scripts for direct execution
COPY migration/scripts/ /app/scripts/

# Copy the column mapping manifest for reference during migration
COPY migration/mapping/ /app/mapping/

# Set file ownership to the non-root application user
RUN chown -R appuser:appgroup /app

# Run as non-root user for security
USER appuser

# The migration module is a batch/utility tool — no exposed port.
# It is run as a one-shot container or interactive shell.
# Default entrypoint allows flexible command execution:
#   docker run migration-tool java -cp app.jar com.jpetstore.migration.DataExporter
#   docker run migration-tool bash /app/scripts/validate-integrity.sh
ENTRYPOINT ["java", "-cp", "app.jar"]
CMD ["com.jpetstore.migration.IntegrityValidator"]
