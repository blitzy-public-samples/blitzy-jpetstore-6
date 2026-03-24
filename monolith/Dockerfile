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
# JPetStore Monolith Dockerfile — Maven Wrapper Build & Cargo/Tomcat 9 Runtime
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
# =============================================================================

FROM openjdk:17-slim

# Create a dedicated non-root user and group for running the application.
# This follows security best practices and is consistent with the api-gateway
# Dockerfile pattern.
RUN groupadd --system --gid 1001 appgroup \
    && useradd --system --uid 1001 --gid appgroup --shell /bin/false appuser

# Copy the entire repository into the container. The build context is the
# repository root, so this includes the parent POM, Maven Wrapper, .mvn/
# directory, and all module sources.
COPY . /usr/src/myapp
WORKDIR /usr/src/myapp

# Make the Maven Wrapper executable and build the monolith WAR.
# -pl monolith : Build only the monolith module
# -am          : Also build any modules that monolith depends on (parent POM)
# -DskipTests  : Tests are executed separately in the CI pipeline
RUN chmod +x mvnw && ./mvnw clean package -pl monolith -am -B -DskipTests

# Set ownership of the working directory to the non-root user
RUN chown -R appuser:appgroup /usr/src/myapp

# Run as non-root user for security
USER appuser

# Expose the monolith's internal port (Tomcat 9 serves on 8080)
EXPOSE 8080

# Start the monolith using Cargo Maven plugin with Tomcat 9 profile.
# The -pl monolith flag ensures Cargo runs in the monolith module context.
CMD ["./mvnw", "cargo:run", "-P", "tomcat9", "-pl", "monolith"]
