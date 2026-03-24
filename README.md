MyBatis JPetStore
=================

[![Java CI](https://github.com/mybatis/jpetstore-6/actions/workflows/ci.yaml/badge.svg)](https://github.com/mybatis/jpetstore-6/actions/workflows/ci.yaml)
[![Container Support](https://github.com/mybatis/jpetstore-6/actions/workflows/support.yaml/badge.svg)](https://github.com/mybatis/jpetstore-6/actions/workflows/support.yaml)
[![Coverage Status](https://coveralls.io/repos/github/mybatis/jpetstore-6/badge.svg?branch=master)](https://coveralls.io/github/mybatis/jpetstore-6?branch=master)
[![License](https://img.shields.io/:license-apache-brightgreen.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

![mybatis-jpetstore](https://mybatis.org/images/mybatis-logo.png)

JPetStore 6 is a full web application built on top of MyBatis 3, Spring 5 and Stripes.
It has been decomposed from a single monolith WAR into a microservices architecture using
the **Strangler Fig pattern**, enabling incremental migration with zero downtime and safe
rollback at every stage.

## Architecture Overview

The application is organized as a multi-module Maven project comprising the **preserved
original monolith** and **three independently deployable Spring Boot 3 microservices**,
fronted by an **API Gateway** that progressively routes traffic from the monolith to the
new services via runtime-configurable per-service routing flags.

### Design Principles

- **Strangler Fig Pattern** — An API Gateway routes each request to either the monolith or
  the appropriate new service based on per-service flags stored in Redis, switchable at
  runtime without redeployment.
- **Database-per-Service** — Each microservice owns its own PostgreSQL database. No service
  accesses another service's database directly; all cross-service data access goes through
  the owning service's REST API.
- **Stateless Authentication** — JWT tokens issued by the Account Service replace
  session-scoped authentication state, enabling horizontal scalability.
- **Externalized Session State** — Redis replaces HTTP session-scoped state for cart data
  and routing flags.
- **Saga Pattern** — The Order Service uses an orchestration-based Saga to coordinate the
  distributed order transaction (order write + inventory decrement) across services with
  compensating actions for failure recovery.

### Bounded Contexts

| Bounded Context | New Service | Owned Tables | Source Classes |
|-----------------|-------------|--------------|----------------|
| Account/User Management | Account Service | `account`, `profile`, `signon`, `bannerdata` | `AccountService`, `AccountActionBean` |
| Catalog/Inventory | Catalog Service | `category`, `product`, `item`, `inventory`, `supplier` | `CatalogService`, `CatalogActionBean` |
| Order/Cart | Order Service | `orders`, `orderstatus`, `lineitem` | `OrderService`, `CartActionBean`, `OrderActionBean` |

### Services

| Service | Port | Technology | Description |
|---------|------|------------|-------------|
| API Gateway | 8080 | Spring Cloud Gateway | Single entry point, request routing, JWT authentication enforcement |
| Monolith | 8090 | Stripes / MyBatis / HSQLDB | Original JPetStore WAR — preserved for Strangler Fig coexistence |
| Account Service | 8081 | Spring Boot 3 / JPA / PostgreSQL | User management, authentication, JWT token issuance |
| Catalog Service | 8082 | Spring Boot 3 / JPA / PostgreSQL | Product catalog browsing, search, inventory management |
| Order Service | 8083 | Spring Boot 3 / JPA / PostgreSQL / Redis | Order placement (Saga), externalized cart state management |

### Technology Stack

| Component | Version | Purpose |
|-----------|---------|---------|
| Java | 17 | Runtime for all modules |
| Spring Boot | 3.5.x | Framework for new microservices |
| Spring Cloud Gateway | 2025.0.0 (BOM) | API Gateway routing and filtering |
| PostgreSQL | 16 | Database for microservices (one per service) |
| Redis | 7 | Routing flags, externalized cart/session state |
| Liquibase | 4.31.x (managed) | Database schema management |
| JWT (jjwt) | 0.12.6 | Stateless authentication tokens |
| Stripes | 1.6.0 | Monolith MVC framework (preserved) |
| MyBatis | 3.5.19 | Monolith ORM (preserved) |
| HSQLDB | 2.7.4 | Monolith embedded database (preserved) |

## Project Structure

```
jpetstore-6/
├── pom.xml                  # Parent POM (multi-module)
├── docker-compose.yml       # Full stack: Gateway, services, DBs, Redis
├── monolith/                # Original JPetStore WAR (preserved)
├── api-gateway/             # Spring Cloud Gateway
├── account-service/         # Account/User bounded context
├── catalog-service/         # Catalog/Inventory bounded context
├── order-service/           # Order/Cart bounded context
├── migration/               # Data migration scripts and tooling
└── docs/                    # Architecture and API documentation
```

Essentials
----------

* [See the docs](http://www.mybatis.org/jpetstore-6)

## Other versions that you may want to know about

- JPetstore on top of Spring, Spring MVC, MyBatis 3, and Spring Security https://github.com/making/spring-jpetstore
- JPetstore with Vaadin and Spring Boot with Java Config https://github.com/igor-baiborodine/jpetstore-6-vaadin-spring-boot
- JPetstore on MyBatis Spring Boot Starter https://github.com/kazuki43zoo/mybatis-spring-boot-jpetstore

## Prerequisites

- **Java 17** (OpenJDK or equivalent)
- **Docker** and **Docker Compose** (for full-stack deployment)
- **Maven** 3.9+ (or use the included Maven Wrapper `./mvnw`)

## Build Instructions

### Build All Modules

Build the entire multi-module project (monolith + all microservices + migration tooling):

```bash
./mvnw clean package                   # Build all modules with tests
./mvnw clean package -DskipTests       # Build all modules, skip tests
```

### Build Individual Modules

```bash
./mvnw package -B -pl monolith              # Build monolith only
./mvnw package -B -pl account-service       # Build Account Service only
./mvnw package -B -pl catalog-service       # Build Catalog Service only
./mvnw package -B -pl order-service         # Build Order Service only
./mvnw package -B -pl api-gateway           # Build API Gateway only
./mvnw package -B -pl migration             # Build migration tooling only
```

## Run on Application Server (Monolith)

Running the original JPetStore monolith under Tomcat (using the [cargo-maven2-plugin](https://codehaus-cargo.github.io/cargo/Maven2+plugin.html)).

- Clone this repository

  ```
  $ git clone https://github.com/mybatis/jpetstore-6.git
  ```

- Build the monolith WAR file

  ```
  $ cd jpetstore-6
  $ ./mvnw clean package -pl monolith
  ```

- Startup the Tomcat server and deploy web application

  ```
  $ ./mvnw cargo:run -P tomcat90 -pl monolith
  ```

  > Note:
  >
  > We provide maven profiles per application server as follow:
  >
  > | Profile        | Description |
  > | -------------- | ----------- |
  > | tomcat90       | Running under the Tomcat 9.0 |
  > | tomee80        | Running under the TomEE 8.0(Java EE 8) |
  > | wildfly26      | Running under the WildFly 26(Java EE 8) |
  > | liberty-ee8    | Running under the WebSphere Liberty(Java EE 8) |
  > | jetty          | Running under the Jetty 12 (Java EE 8) |
  > | glassfish5 (disabled)     | Running under the GlassFish 5(Java EE 8) |
  > | payara5        | Running under the Payara 5 (Java EE 8) |
  > | resin          | Running under the Resin 4 |

- Run application in browser http://localhost:8080/jpetstore/
- Press Ctrl-C to stop the server.

## Run on Docker

### Full Microservices Stack

Start all services, PostgreSQL databases, Redis, and the API Gateway using Docker Compose:

```bash
docker compose up -d
```

This starts the following containers:

| Container | Port | Description |
|-----------|------|-------------|
| `api-gateway` | 8080 | API Gateway (public entry point) |
| `monolith` | 8090 | Original JPetStore monolith |
| `account-service` | 8081 | Account/User microservice |
| `catalog-service` | 8082 | Catalog/Inventory microservice |
| `order-service` | 8083 | Order/Cart microservice |
| `postgres-account` | 5432 | PostgreSQL for Account Service |
| `postgres-catalog` | 5433 | PostgreSQL for Catalog Service |
| `postgres-order` | 5434 | PostgreSQL for Order Service |
| `redis` | 6379 | Redis for routing flags and cart state |

Access the application at http://localhost:8080/

To stop all services:

```bash
docker compose down
```

### Monolith Only (Legacy)

```bash
cd monolith
docker build . -t jpetstore
docker run -p 8080:8080 jpetstore
```

## Data Migration

The `migration/` module contains scripts and tooling to migrate data from the monolith's
HSQLDB database to the three per-service PostgreSQL databases.

### Quick Start

```bash
# 1. Provision PostgreSQL databases
./migration/scripts/provision-postgres.sh

# 2. Export data from HSQLDB
./migration/scripts/export-hsqldb.sh

# 3. Load data into PostgreSQL (idempotent)
./migration/scripts/load-data.sh

# 4. Validate data integrity (7-check gate — must pass before cutover)
./migration/scripts/validate-integrity.sh
```

For the complete migration procedure, including dual-write setup, rollback procedures,
and the 7-check validation gate, see [docs/migration-guide.md](docs/migration-guide.md).

For the detailed column-name mapping between HSQLDB and PostgreSQL, see
[migration/mapping/column-mapping-manifest.md](migration/mapping/column-mapping-manifest.md).

## Testing

### Monolith Unit Tests

Run the monolith's unit and mapper tests (against embedded HSQLDB):

```bash
./mvnw test -B -pl monolith
```

### Monolith Integration Tests

Perform integration tests for screen transition (Selenide-based):

```bash
./mvnw clean verify -P tomcat90 -pl monolith
```

### Microservice Tests

Each microservice includes unit tests and integration tests. Integration tests use
[Testcontainers](https://testcontainers.com/) with PostgreSQL — no external database setup
required.

```bash
# Run tests for a specific service
./mvnw test -B -pl account-service
./mvnw test -B -pl catalog-service
./mvnw test -B -pl order-service

# Run all tests across all modules
./mvnw test -B
```

### Test Infrastructure

| Module | Test Database | Test Framework |
|--------|--------------|----------------|
| Monolith | Embedded HSQLDB | JUnit 5, Mockito, Selenide |
| Account Service | Testcontainers PostgreSQL | JUnit 5, Mockito, Spring Boot Test |
| Catalog Service | Testcontainers PostgreSQL | JUnit 5, Mockito, Spring Boot Test |
| Order Service | Testcontainers PostgreSQL | JUnit 5, Mockito, Spring Boot Test |

## Documentation

| Document | Description |
|----------|-------------|
| [Architecture](docs/architecture.md) | Detailed architecture documentation — bounded contexts, design patterns, inter-service communication, Saga pattern, session state externalization |
| [API Contracts](docs/api-contracts.md) | REST API contracts for Account, Catalog, and Order services — endpoints, request/response schemas, authentication, error handling |
| [Migration Guide](docs/migration-guide.md) | Step-by-step data migration runbook — HSQLDB export, PostgreSQL import, validation gate, cutover procedure, rollback instructions |

## Service Cutover

Services are cut over from the monolith one at a time using the Strangler Fig pattern.
The recommended cutover order is based on dependency analysis and risk assessment:

### Cutover Order

1. **Catalog Service** (first) — Lowest risk. Predominantly read-only operations. Validates
   the infrastructure pattern (Docker, PostgreSQL, API Gateway routing, dual-write) on the
   simplest service.

2. **Account Service** (second) — Medium risk. Self-contained write operations (registration,
   profile update). Validates JWT-based authentication. Depends on Catalog Service for
   personalization (already live from step 1).

3. **Order Service** (last) — Highest risk. Contains the distributed order transaction
   (Saga pattern). Depends on both Catalog Service (inventory decrement) and Account Service
   (user verification), both already live.

### Routing Flags

Per-service routing flags are stored in Redis and can be switched at runtime without
redeployment:

```bash
# Check current routing for a service
redis-cli GET routing.flag.catalog-service
# Returns: "monolith" or "microservice"

# Switch Catalog Service traffic to the new microservice
redis-cli SET routing.flag.catalog-service "microservice"

# Roll back to the monolith (instant, safe before dual-write is disabled)
redis-cli SET routing.flag.catalog-service "monolith"
```

### Dual-Write Coexistence

During the coexistence window, a dual-write mechanism keeps both PostgreSQL (primary) and
HSQLDB (secondary) in sync, enabling safe rollback at any point before dual-write is
disabled. Key characteristics:

- **Write direction**: PostgreSQL → HSQLDB (async propagation, ≤5 second lag)
- **Conflict detection**: `last_modified_timestamp` comparison
- **Observation window**: Minimum 48 hours after each cutover before disabling dual-write
- **Point of no return**: Rollback to the monolith is no longer safe after dual-write is
  disabled for a service

For the complete cutover procedure and rollback instructions, see
[docs/migration-guide.md](docs/migration-guide.md).

## License

JPetStore 6 is released under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.html).
