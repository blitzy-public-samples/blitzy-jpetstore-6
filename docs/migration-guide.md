# JPetStore Data Migration Guide — HSQLDB to PostgreSQL

## Overview

This runbook provides a complete, step-by-step procedure for migrating data from the JPetStore 6 monolith's single embedded HSQLDB database to three independent PostgreSQL databases — one for each microservice bounded context:

| Target Database        | Owning Service   | Tables Migrated                                        |
|------------------------|------------------|--------------------------------------------------------|
| `jpetstore_account`    | Account Service  | `account`, `profile`, `signon`, `bannerdata`           |
| `jpetstore_catalog`    | Catalog Service  | `category`, `product`, `item`, `inventory`, `supplier` |
| `jpetstore_order`      | Order Service    | `orders`, `orderstatus`, `lineitem`                    |

The `sequence` table is **not** migrated — it is replaced by a PostgreSQL native sequence (`order_id_seq`) in the Order Service database.

This migration is part of the Strangler Fig decomposition of the JPetStore monolith. The migration is executed once per environment (staging, then production) and must pass a mandatory 7-check validation gate before any service cutover is permitted. The entire procedure is designed for zero data loss and full rollback safety until the dual-write coexistence window is explicitly closed.

---

## Table of Contents

1. [Pre-Migration Checklist](#1-pre-migration-checklist)
2. [HSQLDB → PostgreSQL Schema Mapping](#2-hsqldb--postgresql-schema-mapping)
   - 2.1 [Column Name Mapping Rules](#21-column-name-mapping-rules)
   - 2.2 [Account Service Database — `jpetstore_account`](#22-account-service-database--jpetstore_account)
   - 2.3 [Catalog Service Database — `jpetstore_catalog`](#23-catalog-service-database--jpetstore_catalog)
   - 2.4 [Order Service Database — `jpetstore_order`](#24-order-service-database--jpetstore_order)
   - 2.5 [Data Type Mapping](#25-data-type-mapping)
   - 2.6 [Cross-Service Foreign Key Removal](#26-cross-service-foreign-key-removal)
   - 2.7 [PostgreSQL Sequence Replacement](#27-postgresql-sequence-replacement)
3. [Data Export Procedure](#3-data-export-procedure)
4. [Data Load Procedure](#4-data-load-procedure)
5. [7-Check Validation Gate](#5-7-check-validation-gate)
6. [Service Cutover Procedure](#6-service-cutover-procedure)
7. [Dual-Write Enable/Disable Procedure](#7-dual-write-enabledisable-procedure)
8. [Rollback Procedures](#8-rollback-procedures)
9. [Post-Migration Cleanup](#9-post-migration-cleanup)
10. [Critical Rules Reference](#10-critical-rules-reference)

---

## 1. Pre-Migration Checklist

Every item in this checklist **must** be confirmed before proceeding to any subsequent step. Do not skip items.

### 1.1 HSQLDB Backup and Baseline

- [ ] **Full verified backup** of the HSQLDB database files has been created and stored in a secure, separate location. The backup must be a complete copy of the HSQLDB data directory.
- [ ] **Baseline row counts** have been recorded for all 13 tables by querying the live HSQLDB instance. Record the counts in the table below:

| # | Bounded Context | Table Name     | Expected Seed Count | Actual Live Count |
|---|-----------------|----------------|--------------------:|------------------:|
| 1 | Account         | `signon`       |                   2 |       ___________ |
| 2 | Account         | `account`      |                   2 |       ___________ |
| 3 | Account         | `profile`      |                   2 |       ___________ |
| 4 | Account         | `bannerdata`   |                   5 |       ___________ |
| 5 | Catalog         | `category`     |                   5 |       ___________ |
| 6 | Catalog         | `product`      |                  16 |       ___________ |
| 7 | Catalog         | `item`         |                  28 |       ___________ |
| 8 | Catalog         | `inventory`    |                  28 |       ___________ |
| 9 | Catalog         | `supplier`     |                   2 |       ___________ |
| 10| Order           | `orders`       |                   0 |       ___________ |
| 11| Order           | `orderstatus`  |                   0 |       ___________ |
| 12| Order           | `lineitem`     |                   0 |       ___________ |
| 13| Order           | `sequence`     |                   1 |       ___________ |

> **Note:** The "Expected Seed Count" column reflects the seed data from `jpetstore-hsqldb-dataload.sql`. A production system will have additional rows created through normal application usage.

Use the following SQL to capture baseline counts:

```sql
-- Execute against the live HSQLDB instance
SELECT 'signon' AS table_name, COUNT(*) AS row_count FROM signon
UNION ALL SELECT 'account', COUNT(*) FROM account
UNION ALL SELECT 'profile', COUNT(*) FROM profile
UNION ALL SELECT 'bannerdata', COUNT(*) FROM bannerdata
UNION ALL SELECT 'category', COUNT(*) FROM category
UNION ALL SELECT 'product', COUNT(*) FROM product
UNION ALL SELECT 'item', COUNT(*) FROM item
UNION ALL SELECT 'inventory', COUNT(*) FROM inventory
UNION ALL SELECT 'supplier', COUNT(*) FROM supplier
UNION ALL SELECT 'orders', COUNT(*) FROM orders
UNION ALL SELECT 'orderstatus', COUNT(*) FROM orderstatus
UNION ALL SELECT 'lineitem', COUNT(*) FROM lineitem
UNION ALL SELECT 'sequence', COUNT(*) FROM sequence;
```

- [ ] **Record the current maximum order ID** (needed for sequence starting value):

```sql
SELECT COALESCE(MAX(orderid), 0) AS max_order_id FROM orders;
```

Record the result: `max_order_id = ___________`

### 1.2 Schema Audit

- [ ] **Schema audit passed**: The live HSQLDB schema has been verified to match the reference schema definition at `src/main/resources/database/jpetstore-hsqldb-schema.sql`. Confirm that all 13 tables exist, all columns are present with correct types, and all constraints (PK, FK, indexes) match.

Use the following HSQLDB query to list all tables and columns:

```sql
SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH,
       NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE
FROM INFORMATION_SCHEMA.COLUMNS
WHERE TABLE_SCHEMA = 'PUBLIC'
ORDER BY TABLE_NAME, ORDINAL_POSITION;
```

### 1.3 Infrastructure Readiness

- [ ] **All three microservices** (Account Service, Catalog Service, Order Service) are built successfully and passing unit tests.
- [ ] **Three PostgreSQL 16 instances** are provisioned, accessible from the migration host, and empty:
  - `postgres-account` → database `jpetstore_account` (port 5432)
  - `postgres-catalog` → database `jpetstore_catalog` (port 5433)
  - `postgres-order` → database `jpetstore_order` (port 5434)
- [ ] **Redis 7 instance** is provisioned and accessible (port 6379) — required for routing flags and externalized cart state.
- [ ] **API Gateway** is deployed with all routing flags set to `"monolith"` (default safe state):

```bash
redis-cli SET routing.flag.account-service "monolith"
redis-cli SET routing.flag.catalog-service "monolith"
redis-cli SET routing.flag.order-service "monolith"
```

- [ ] **Connectivity verified** from the migration host to all PostgreSQL instances and Redis:

```bash
# PostgreSQL connectivity
psql -h postgres-account -p 5432 -U account_user -d jpetstore_account -c "SELECT 1;"
psql -h postgres-catalog -p 5433 -U catalog_user -d jpetstore_catalog -c "SELECT 1;"
psql -h postgres-order   -p 5434 -U order_user   -d jpetstore_order   -c "SELECT 1;"

# Redis connectivity
redis-cli -h redis -p 6379 PING
```

### 1.4 Migration Tooling Ready

- [ ] The `migration/` module is built: `./mvnw package -B -pl migration`
- [ ] The following scripts are executable:
  - `migration/scripts/export-hsqldb.sh`
  - `migration/scripts/provision-postgres.sh`
  - `migration/scripts/load-data.sh`
  - `migration/scripts/validate-integrity.sh`

---

## 2. HSQLDB → PostgreSQL Schema Mapping

### 2.1 Column Name Mapping Rules

The HSQLDB schema uses lowercase column names in the DDL, but HSQLDB normalizes unquoted identifiers to uppercase internally. PostgreSQL normalizes unquoted identifiers to lowercase. The mapping rules are:

| Rule | HSQLDB | PostgreSQL | Example |
|------|--------|------------|---------|
| Column names | Uppercase internal representation | Lowercase (snake_case) | `USERID` → `userid` |
| Table names | Uppercase internal representation | Lowercase | `ACCOUNT` → `account` |
| String types | `VARCHAR(n)` | `varchar(n)` | Identical semantics |
| Integer types | `INT` / `INTEGER` | `integer` | Identical semantics |
| Decimal types | `DECIMAL(p,s)` | `numeric(p,s)` | Exact precision/scale preserved |
| Date types | `DATE` | `timestamp with time zone` | Widened to timezone-aware timestamp (Order service: `order_date`, `orderstatus.timestamp`) |

All column names, types, and constraints below are derived directly from the reference schema at `src/main/resources/database/jpetstore-hsqldb-schema.sql`.

---

### 2.2 Account Service Database — `jpetstore_account`

Four tables are migrated to the Account Service's PostgreSQL database.

#### Table 1: `signon`

| # | HSQLDB Column | PostgreSQL Column | Data Type     | Nullable | Constraint |
|---|---------------|-------------------|---------------|----------|------------|
| 1 | `USERNAME`    | `username`        | varchar(25)   | NOT NULL | **PK** (`pk_signon`) |
| 2 | `PASSWORD`    | `password`        | varchar(25)   | NOT NULL | — |

#### Table 2: `account`

| # | HSQLDB Column | PostgreSQL Column | Data Type     | Nullable | Constraint |
|---|---------------|-------------------|---------------|----------|------------|
| 1 | `USERID`      | `userid`          | varchar(80)   | NOT NULL | **PK** (`pk_account`) |
| 2 | `EMAIL`       | `email`           | varchar(80)   | NOT NULL | — |
| 3 | `FIRSTNAME`   | `firstname`       | varchar(80)   | NOT NULL | — |
| 4 | `LASTNAME`    | `lastname`        | varchar(80)   | NOT NULL | — |
| 5 | `STATUS`      | `status`          | varchar(2)    | NULL     | — |
| 6 | `ADDR1`       | `addr1`           | varchar(80)   | NOT NULL | — |
| 7 | `ADDR2`       | `addr2`           | varchar(40)   | NULL     | — |
| 8 | `CITY`        | `city`            | varchar(80)   | NOT NULL | — |
| 9 | `STATE`       | `state`           | varchar(80)   | NOT NULL | — |
| 10| `ZIP`         | `zip`             | varchar(20)   | NOT NULL | — |
| 11| `COUNTRY`     | `country`         | varchar(20)   | NOT NULL | — |
| 12| `PHONE`       | `phone`           | varchar(80)   | NOT NULL | — |

#### Table 3: `profile`

| # | HSQLDB Column  | PostgreSQL Column  | Data Type     | Nullable | Constraint |
|---|----------------|---------------------|---------------|----------|------------|
| 1 | `USERID`       | `userid`            | varchar(80)   | NOT NULL | **PK** (`pk_profile`) |
| 2 | `LANGPREF`     | `langpref`          | varchar(80)   | NOT NULL | — |
| 3 | `FAVCATEGORY`  | `favcategory`       | varchar(30)   | NULL     | — |
| 4 | `MYLISTOPT`    | `mylistopt`         | integer       | NULL     | — |
| 5 | `BANNEROPT`    | `banneropt`         | integer       | NULL     | — |

#### Table 4: `bannerdata`

| # | HSQLDB Column  | PostgreSQL Column  | Data Type     | Nullable | Constraint |
|---|----------------|---------------------|---------------|----------|------------|
| 1 | `FAVCATEGORY`  | `favcategory`       | varchar(80)   | NOT NULL | **PK** (`pk_bannerdata`) |
| 2 | `BANNERNAME`   | `bannername`        | varchar(255)  | NULL     | — |

**Intra-service foreign keys:** None. The `profile.userid` and `signon.username` logically reference `account.userid`, but the original HSQLDB schema does not define these as FK constraints, so they are not added in PostgreSQL either.

---

### 2.3 Catalog Service Database — `jpetstore_catalog`

Five tables are migrated to the Catalog Service's PostgreSQL database.

#### Table 5: `supplier`

| # | HSQLDB Column | PostgreSQL Column | Data Type     | Nullable | Constraint |
|---|---------------|-------------------|---------------|----------|------------|
| 1 | `SUPPID`      | `suppid`          | integer       | NOT NULL | **PK** (`pk_supplier`) |
| 2 | `NAME`        | `name`            | varchar(80)   | NULL     | — |
| 3 | `STATUS`      | `status`          | varchar(2)    | NOT NULL | — |
| 4 | `ADDR1`       | `addr1`           | varchar(80)   | NULL     | — |
| 5 | `ADDR2`       | `addr2`           | varchar(80)   | NULL     | — |
| 6 | `CITY`        | `city`            | varchar(80)   | NULL     | — |
| 7 | `STATE`       | `state`           | varchar(80)   | NULL     | — |
| 8 | `ZIP`         | `zip`             | varchar(5)    | NULL     | — |
| 9 | `PHONE`       | `phone`           | varchar(80)   | NULL     | — |

#### Table 6: `category`

| # | HSQLDB Column | PostgreSQL Column | Data Type     | Nullable | Constraint |
|---|---------------|-------------------|---------------|----------|------------|
| 1 | `CATID`       | `catid`           | varchar(10)   | NOT NULL | **PK** (`pk_category`) |
| 2 | `NAME`        | `name`            | varchar(80)   | NULL     | — |
| 3 | `DESCN`       | `descn`           | varchar(255)  | NULL     | — |

#### Table 7: `product`

| # | HSQLDB Column | PostgreSQL Column | Data Type     | Nullable | Constraint |
|---|---------------|-------------------|---------------|----------|------------|
| 1 | `PRODUCTID`   | `productid`       | varchar(10)   | NOT NULL | **PK** (`pk_product`) |
| 2 | `CATEGORY`    | `category`        | varchar(10)   | NOT NULL | **FK** → `category(catid)` (`fk_product_1`) |
| 3 | `NAME`        | `name`            | varchar(80)   | NULL     | — |
| 4 | `DESCN`       | `descn`           | varchar(255)  | NULL     | — |

**Indexes:**
- `productCat` on `product(category)`
- `productName` on `product(name)`

#### Table 8: `item`

| # | HSQLDB Column | PostgreSQL Column | Data Type      | Nullable | Constraint |
|---|---------------|-------------------|----------------|----------|------------|
| 1 | `ITEMID`      | `itemid`          | varchar(10)    | NOT NULL | **PK** (`pk_item`) |
| 2 | `PRODUCTID`   | `productid`       | varchar(10)    | NOT NULL | **FK** → `product(productid)` (`fk_item_1`) |
| 3 | `LISTPRICE`   | `listprice`       | numeric(10,2)  | NULL     | — |
| 4 | `UNITCOST`    | `unitcost`        | numeric(10,2)  | NULL     | — |
| 5 | `SUPPLIER`    | `supplier`        | integer        | NULL     | **FK** → `supplier(suppid)` (`fk_item_2`) |
| 6 | `STATUS`      | `status`          | varchar(2)     | NULL     | — |
| 7 | `ATTR1`       | `attr1`           | varchar(80)    | NULL     | — |
| 8 | `ATTR2`       | `attr2`           | varchar(80)    | NULL     | — |
| 9 | `ATTR3`       | `attr3`           | varchar(80)    | NULL     | — |
| 10| `ATTR4`       | `attr4`           | varchar(80)    | NULL     | — |
| 11| `ATTR5`       | `attr5`           | varchar(80)    | NULL     | — |

**Index:** `itemProd` on `item(productid)`

#### Table 9: `inventory`

| # | HSQLDB Column | PostgreSQL Column | Data Type   | Nullable | Constraint |
|---|---------------|-------------------|-------------|----------|------------|
| 1 | `ITEMID`      | `itemid`          | varchar(10) | NOT NULL | **PK** (`pk_inventory`) |
| 2 | `QTY`         | `qty`             | integer     | NOT NULL | — |
| 3 | _(NEW)_       | `version`         | integer     | NOT NULL | Default `0`; used for JPA `@Version` optimistic locking |

**Intra-service foreign keys (Catalog):**
- `product.category` → `category.catid` (preserved)
- `item.productid` → `product.productid` (preserved)
- `item.supplier` → `supplier.suppid` (preserved)

---

### 2.4 Order Service Database — `jpetstore_order`

Three tables (plus one new Saga tracking table) are migrated to the Order Service's PostgreSQL database. The `sequence` table is **not** migrated. All Order service column names use `snake_case` convention (unlike Account and Catalog services which retain HSQLDB-identical names).

#### Table 10: `orders`

| # | HSQLDB Column       | PostgreSQL Column     | Data Type                  | Nullable | Constraint |
|---|---------------------|-----------------------|----------------------------|----------|------------|
| 1 | `ORDERID`           | `order_id`            | integer                    | NOT NULL | **PK** (`pk_orders`), default from `order_id_seq` |
| 2 | `USERID`            | `userid`              | varchar(80)                | NOT NULL | Cross-service ref (no FK); retains original HSQLDB name |
| 3 | `ORDERDATE`         | `order_date`          | timestamp with time zone   | NOT NULL | Type widened from `DATE` to timezone-aware timestamp |
| 4 | `SHIPADDR1`         | `ship_addr1`          | varchar(80)                | NOT NULL | — |
| 5 | `SHIPADDR2`         | `ship_addr2`          | varchar(80)                | NULL     | — |
| 6 | `SHIPCITY`          | `ship_city`           | varchar(80)                | NOT NULL | — |
| 7 | `SHIPSTATE`         | `ship_state`          | varchar(80)                | NOT NULL | — |
| 8 | `SHIPZIP`           | `ship_zip`            | varchar(20)                | NOT NULL | — |
| 9 | `SHIPCOUNTRY`       | `ship_country`        | varchar(20)                | NOT NULL | — |
| 10| `BILLADDR1`         | `bill_addr1`          | varchar(80)                | NOT NULL | — |
| 11| `BILLADDR2`         | `bill_addr2`          | varchar(80)                | NULL     | — |
| 12| `BILLCITY`          | `bill_city`           | varchar(80)                | NOT NULL | — |
| 13| `BILLSTATE`         | `bill_state`          | varchar(80)                | NOT NULL | — |
| 14| `BILLZIP`           | `bill_zip`            | varchar(20)                | NOT NULL | — |
| 15| `BILLCOUNTRY`       | `bill_country`        | varchar(20)                | NOT NULL | — |
| 16| `COURIER`           | `courier`             | varchar(80)                | NOT NULL | Already snake_case |
| 17| `TOTALPRICE`        | `total_price`         | numeric(10,2)              | NOT NULL | — |
| 18| `BILLTOFIRSTNAME`   | `bill_to_first_name`  | varchar(80)                | NOT NULL | — |
| 19| `BILLTOLASTNAME`    | `bill_to_last_name`   | varchar(80)                | NOT NULL | — |
| 20| `SHIPTOFIRSTNAME`   | `ship_to_first_name`  | varchar(80)                | NOT NULL | — |
| 21| `SHIPTOLASTNAME`    | `ship_to_last_name`   | varchar(80)                | NOT NULL | — |
| 22| `CREDITCARD`        | `credit_card`         | varchar(80)                | NOT NULL | — |
| 23| `EXPRDATE`          | `expr_date`           | varchar(7)                 | NOT NULL | — |
| 24| `CARDTYPE`          | `card_type`           | varchar(80)                | NOT NULL | — |
| 25| `LOCALE`            | `locale`              | varchar(80)                | NOT NULL | Already snake_case |
| 26| _(NEW)_             | `status`              | varchar(20)                | NULL     | Saga orchestration state: `PENDING`, `CONFIRMED`, `FAILED` |

> **Cross-service reference:** `orders.userid` references `account.userid` in the Account Service database. This is **not** a database-level FK constraint — it is enforced at the application layer by the Order Service's REST client calling the Account Service's `GET /api/accounts/{username}` endpoint.

#### Table 11: `orderstatus`

| # | HSQLDB Column | PostgreSQL Column | Data Type                | Nullable | Constraint |
|---|---------------|-------------------|--------------------------|----------|------------|
| 1 | `ORDERID`     | `order_id`        | integer                  | NOT NULL | **Composite PK** (`pk_orderstatus`) |
| 2 | `LINENUM`     | `line_num`        | integer                  | NOT NULL | **Composite PK** (`pk_orderstatus`) |
| 3 | `TIMESTAMP`   | `timestamp`       | timestamp with time zone | NOT NULL | Type widened from `DATE` to timezone-aware timestamp |
| 4 | `STATUS`      | `status`          | varchar(20)              | NOT NULL | Widened from `varchar(2)` to accommodate Saga state values (e.g., `COMPLETED`, `COMPENSATING`) |

#### Table 12: `lineitem`

| # | HSQLDB Column | PostgreSQL Column | Data Type      | Nullable | Constraint |
|---|---------------|-------------------|----------------|----------|------------|
| 1 | `ORDERID`     | `order_id`        | integer        | NOT NULL | **Composite PK** (`pk_lineitem`) |
| 2 | `LINENUM`     | `line_num`        | integer        | NOT NULL | **Composite PK** (`pk_lineitem`) |
| 3 | `ITEMID`      | `item_id`         | varchar(10)    | NOT NULL | Cross-service ref (no FK) |
| 4 | `QUANTITY`    | `quantity`        | integer        | NOT NULL | Already snake_case |
| 5 | `UNITPRICE`   | `unit_price`      | numeric(10,2)  | NOT NULL | — |

> **Cross-service reference:** `lineitem.item_id` references `item.itemid` in the Catalog Service database. This is **not** a database-level FK constraint — it is enforced at the application layer by the Order Service's REST client calling the Catalog Service's `GET /api/items/{id}` endpoint.

#### Table 13: `sequence` — NOT MIGRATED

The `sequence` table (columns: `name` varchar(30), `nextid` integer) is **not** migrated to any PostgreSQL database. It is replaced by a PostgreSQL native sequence. See [Section 2.7](#27-postgresql-sequence-replacement) for details.

---

### 2.5 Data Type Mapping

Complete HSQLDB → PostgreSQL data type mapping used across all 13 tables:

| HSQLDB Type       | PostgreSQL Type            | Notes |
|-------------------|----------------------------|-------|
| `VARCHAR(n)`      | `varchar(n)`               | Identical semantics; `n` preserved exactly (widened where noted, e.g., `orderstatus.status` from `varchar(2)` to `varchar(20)`) |
| `INT` / `INTEGER` | `integer`                  | 4-byte signed integer in both databases |
| `INT` (boolean)   | `boolean`                  | Account service: `profile.mylistopt` and `profile.banneropt` converted from integer (0/1) to native boolean |
| `DECIMAL(10,2)`   | `numeric(10,2)`            | Exact precision and scale preserved; used for monetary fields (`listprice`, `unitcost`, `total_price`, `unit_price`) |
| `DATE`            | `timestamp with time zone` | Order service: `orders.order_date` and `orderstatus.timestamp` widened to timezone-aware timestamps |

**Special considerations:**

- **Boolean conversion:** The `profile.mylistopt` and `profile.banneropt` columns are converted from HSQLDB `integer` (values `0`/`1`) to PostgreSQL native `boolean` (`true`/`false`). The Account Service's JPA entities use `Boolean` type for these fields.
- **Date type widening:** The Order service widens HSQLDB `DATE` columns to PostgreSQL `timestamp with time zone` for `orders.order_date` and `orderstatus.timestamp`, enabling timezone-aware date tracking for distributed operations.
- **Status field widening:** `orderstatus.status` is widened from `varchar(2)` to `varchar(20)` to accommodate Saga state values (e.g., `COMPLETED`, `COMPENSATING`).
- **NULL handling:** All NULL/NOT NULL constraints are preserved exactly as defined in the source schema. No columns change nullability during migration, except the new `orders.status` column which is nullable (not present in source HSQLDB schema).
- **Monetary precision:** All monetary values use `numeric(10,2)` — no floating-point types are used, preventing rounding errors.
- **VARCHAR lengths:** All VARCHAR lengths are preserved exactly. The `supplier.zip` field is `varchar(5)` (shorter than other zip fields at `varchar(20)`), matching the source schema precisely.

---

### 2.6 Cross-Service Foreign Key Removal

The following foreign key relationships existed implicitly in the monolith's single-database design (as cross-table references) and are **removed** as database constraints in the decomposed architecture:

| FK Relationship | Direction | Reason for Removal | Application-Layer Enforcement |
|----------------|-----------|--------------------|-----------------------------|
| `lineitem.itemid` → `item.itemid` | Order Service → Catalog Service | Tables reside in separate databases owned by different services | Order Service validates item existence via REST call: `GET /api/items/{itemId}` to Catalog Service |
| `orders.userid` → `account.userid` | Order Service → Account Service | Tables reside in separate databases owned by different services | Order Service validates account existence via REST call: `GET /api/accounts/{username}` to Account Service |

**Intra-service foreign keys are preserved** as database-level constraints:
- `product.category` → `category.catid` (within `jpetstore_catalog`)
- `item.productid` → `product.productid` (within `jpetstore_catalog`)
- `item.supplier` → `supplier.suppid` (within `jpetstore_catalog`)

---

### 2.7 PostgreSQL Sequence Replacement

**Current HSQLDB mechanism (being replaced):**

The monolith uses a `sequence` table with a non-thread-safe read-then-update pattern in `OrderService.getNextId()`:

```java
// Race condition: concurrent calls may read the same nextid value
Sequence sequence = sequenceMapper.getSequence(new Sequence(name, -1));
sequenceMapper.updateSequence(new Sequence(name, sequence.getNextId() + 1));
return sequence.getNextId();
```

The seed data initializes `ordernum` to 1000: `INSERT INTO sequence VALUES('ordernum', 1000);`

**PostgreSQL replacement:**

The `sequence` table is **not** migrated. Instead, the Order Service database uses a PostgreSQL native sequence:

```sql
CREATE SEQUENCE order_id_seq START WITH <max_migrated_orderId + 1000>;
```

**Starting value calculation:**

1. Query the maximum order ID from the migrated `orders` table: `SELECT COALESCE(MAX(orderid), 0) FROM orders;`
2. Add a buffer of 1000 to the result.
3. If no orders have been placed (max is 0), start with the HSQLDB seed value plus buffer: `1000 + 1000 = 2000`.
4. The generous buffer of 1000 prevents any ID collisions with orders placed between the export snapshot and the cutover moment.

**Benefits:**
- Thread-safe: PostgreSQL sequences use atomic `nextval()` — no race conditions under any concurrency level.
- No application-level locking needed.
- Higher throughput than table-based ID generation.

The Order Service's JPA entity uses `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")` with `@SequenceGenerator(name = "order_seq", sequenceName = "order_id_seq")`.

---

## 3. Data Export Procedure

### 3.1 Pre-Export Verification

Before starting the export, verify that no schema changes have occurred since the baseline was recorded:

```bash
# Verify baseline row counts are still current
# (If the application is under active use, counts may differ — that is expected)
```

### 3.2 Execute Export

Use the migration module's export script to extract all 13 tables from the live HSQLDB instance:

```bash
# Make script executable (if not already)
chmod +x migration/scripts/export-hsqldb.sh

# Execute the export
# The script connects to the HSQLDB instance and exports each table to CSV/SQL files
./migration/scripts/export-hsqldb.sh \
  --hsqldb-url="jdbc:hsqldb:hsql://localhost:9001/jpetstore" \
  --output-dir="migration/export-data/"
```

**Critical rules for the export:**

1. **Read-only operation:** The export must NOT modify any data in HSQLDB. It uses read-only JDBC connections.
2. **Non-disruptive:** The export must NOT affect running application traffic. It operates on snapshot isolation.
3. **Complete:** All 13 tables are exported, including tables with zero rows (`orders`, `orderstatus`, `lineitem` may be empty in staging environments).
4. **Verified:** The export script produces a manifest file (`export-manifest.json`) containing per-table row counts.

### 3.3 Post-Export Verification

After the export completes, verify row counts match:

```bash
# Review the export manifest
cat migration/export-data/export-manifest.json

# Compare each table's exported row count against the baseline recorded in Section 1.1
# ALL counts must match (or be >= baseline if application was active during export)
```

| Verification Check | Pass Criteria |
|-------------------|---------------|
| All 13 tables exported | Export directory contains files for every table |
| Row counts match baseline | Each table's exported count ≥ baseline count from Section 1.1 |
| No empty files for populated tables | Tables with baseline count > 0 produce non-empty export files |
| Export manifest generated | `export-manifest.json` exists and is valid JSON |

---

## 4. Data Load Procedure

### 4.1 Provision PostgreSQL Databases

Create the three PostgreSQL databases if they do not already exist:

```bash
chmod +x migration/scripts/provision-postgres.sh

# Creates three databases: jpetstore_account, jpetstore_catalog, jpetstore_order
# Creates the jpetstore user with appropriate permissions on each database
./migration/scripts/provision-postgres.sh \
  --postgres-host="localhost" \
  --postgres-admin-user="postgres" \
  --postgres-admin-password="${POSTGRES_ADMIN_PASSWORD}"
```

### 4.2 Run Liquibase Schema Migrations

Each microservice owns its Liquibase changelog. The schemas are created **automatically** when each Spring Boot service starts, because `spring.liquibase.enabled=true` is configured in each service's `application.yml`. No manual Liquibase commands are needed.

To trigger schema creation, simply start the services:

```bash
# Start all services (including PostgreSQL instances) via Docker Compose
docker compose up -d account-service catalog-service order-service

# Verify schemas were created
docker exec postgres-account psql -U account_user -d jpetstore_account -c "\dt"
docker exec postgres-catalog psql -U catalog_user -d jpetstore_catalog -c "\dt"
docker exec postgres-order   psql -U order_user   -d jpetstore_order   -c "\dt"
```

> **Note:** There is no `liquibase-maven-plugin` configured in the service POMs. Liquibase runs exclusively via the `liquibase-core` runtime dependency integrated with Spring Boot's auto-configuration. Manual Liquibase CLI usage is not supported.

### 4.3 Load Data

Use the migration module's idempotent data loading script:

```bash
chmod +x migration/scripts/load-data.sh

./migration/scripts/load-data.sh \
  --input-dir="migration/export-data/" \
  --account-db-url="jdbc:postgresql://postgres-account:5432/jpetstore_account" \
  --catalog-db-url="jdbc:postgresql://postgres-catalog:5433/jpetstore_catalog" \
  --order-db-url="jdbc:postgresql://postgres-order:5434/jpetstore_order" \
  --account-db-user="account_user" \
  --catalog-db-user="catalog_user" \
  --order-db-user="order_user" \
  --db-password="${POSTGRES_PASSWORD}"
```

**Critical data loading rules:**

1. **Idempotent:** The load script uses `INSERT ... ON CONFLICT DO NOTHING` for all tables. Re-running the script against an already-populated database does **not** create duplicate rows.
2. **Load order within Catalog database:** Tables must be loaded in dependency order to satisfy intra-service FKs:
   - `supplier` first (no dependencies)
   - `category` second (no dependencies)
   - `product` third (depends on `category`)
   - `item` fourth (depends on `product` and `supplier`)
   - `inventory` fifth (depends on `item` by convention, though no FK exists)
3. **Load order within Account database:** No inter-table FKs, so order does not matter. Recommended: `signon`, `account`, `profile`, `bannerdata`.
4. **Load order within Order database:** No inter-table FKs (cross-service FKs removed). Recommended: `orders`, `orderstatus`, `lineitem`.
5. **Sequence table is skipped:** The `sequence` table is not loaded into any PostgreSQL database.

### 4.4 Set PostgreSQL Sequence Starting Value

After loading order data, set the `order_id_seq` starting value in the Order Service database:

```sql
-- Connect to jpetstore_order database
-- Calculate: MAX(orderid) + 1000 buffer
DO $$
DECLARE
  max_id integer;
  start_val integer;
BEGIN
  SELECT COALESCE(MAX(orderid), 0) INTO max_id FROM orders;
  start_val := max_id + 1000;
  -- Ensure minimum starting value of 2000 (seed value 1000 + buffer 1000)
  IF start_val < 2000 THEN
    start_val := 2000;
  END IF;
  EXECUTE format('ALTER SEQUENCE order_id_seq RESTART WITH %s', start_val);
  RAISE NOTICE 'order_id_seq set to start with %', start_val;
END $$;
```

Verify the sequence value:

```sql
SELECT last_value, is_called FROM order_id_seq;
-- Expected: last_value >= 2000, is_called = false (if RESTART was used)
```

---

## 5. 7-Check Validation Gate

All seven validation checks **must pass** before any service cutover is permitted. There are no exceptions to this rule. A single failing check blocks the entire cutover process.

### Running the Validation Gate

```bash
chmod +x migration/scripts/validate-integrity.sh

./migration/scripts/validate-integrity.sh \
  --account-db-url="jdbc:postgresql://postgres-account:5432/jpetstore_account" \
  --catalog-db-url="jdbc:postgresql://postgres-catalog:5433/jpetstore_catalog" \
  --order-db-url="jdbc:postgresql://postgres-order:5434/jpetstore_order" \
  --export-manifest="migration/export-data/export-manifest.json" \
  --account-db-user="account_user" \
  --catalog-db-user="catalog_user" \
  --order-db-user="order_user" \
  --db-password="${POSTGRES_PASSWORD}"
```

### Check 1: Row Count Match

**Purpose:** Verify that every row exported from HSQLDB is present in the target PostgreSQL database.

**Procedure:** For each of the 12 migrated tables (all except `sequence`), compare the PostgreSQL row count to the HSQLDB export row count from the export manifest.

```sql
-- Account database
SELECT 'signon' AS table_name, COUNT(*) AS pg_count FROM signon
UNION ALL SELECT 'account', COUNT(*) FROM account
UNION ALL SELECT 'profile', COUNT(*) FROM profile
UNION ALL SELECT 'bannerdata', COUNT(*) FROM bannerdata;

-- Catalog database
SELECT 'supplier' AS table_name, COUNT(*) AS pg_count FROM supplier
UNION ALL SELECT 'category', COUNT(*) FROM category
UNION ALL SELECT 'product', COUNT(*) FROM product
UNION ALL SELECT 'item', COUNT(*) FROM item
UNION ALL SELECT 'inventory', COUNT(*) FROM inventory;

-- Order database
SELECT 'orders' AS table_name, COUNT(*) AS pg_count FROM orders
UNION ALL SELECT 'orderstatus', COUNT(*) FROM orderstatus
UNION ALL SELECT 'lineitem', COUNT(*) FROM lineitem;
```

**Pass criteria:** PostgreSQL count = Export manifest count for every table.

### Check 2: Primary Key Uniqueness

**Purpose:** Verify that no duplicate primary keys exist in any migrated table.

**Procedure:** For each table, verify that the count of distinct PK values equals the total row count.

```sql
-- Example for account table (single-column PK)
SELECT COUNT(*) AS total, COUNT(DISTINCT userid) AS distinct_pks FROM account;

-- Example for lineitem table (composite PK)
SELECT COUNT(*) AS total,
       COUNT(DISTINCT (orderid, linenum)) AS distinct_pks FROM lineitem;
```

**Pass criteria:** `total = distinct_pks` for every table.

### Check 3: Intra-Service FK Integrity

**Purpose:** Verify that all within-service foreign key references are satisfied.

**Procedure:** Check for orphaned rows — child records whose FK values do not exist in the parent table.

```sql
-- Catalog database: product.category → category.catid
SELECT COUNT(*) AS orphaned_products
FROM product p
WHERE NOT EXISTS (SELECT 1 FROM category c WHERE c.catid = p.category);

-- Catalog database: item.productid → product.productid
SELECT COUNT(*) AS orphaned_items_product
FROM item i
WHERE NOT EXISTS (SELECT 1 FROM product p WHERE p.productid = i.productid);

-- Catalog database: item.supplier → supplier.suppid (supplier is nullable)
SELECT COUNT(*) AS orphaned_items_supplier
FROM item i
WHERE i.supplier IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM supplier s WHERE s.suppid = i.supplier);
```

**Pass criteria:** All orphan counts = 0.

### Check 4: Cross-Service Reference Integrity

**Purpose:** Verify that all cross-service references can be resolved, even though they are not enforced as database FK constraints.

**Procedure:** Check that every `orders.userid` exists in the Account database and every `lineitem.itemid` exists in the Catalog database.

```sql
-- Order database → Account database: orders.userid exists in account.userid
-- (Requires cross-database query or application-level check)
-- Using the migration module's IntegrityValidator:
-- For each distinct userid in orders, verify it exists in jpetstore_account.account

-- Order database → Catalog database: lineitem.itemid exists in item.itemid
-- For each distinct itemid in lineitem, verify it exists in jpetstore_catalog.item
```

**Pass criteria:** Every cross-service reference can be resolved. Zero unresolvable references.

### Check 5: Sequence Safety

**Purpose:** Verify that the PostgreSQL `order_id_seq` current value exceeds the maximum migrated order ID.

```sql
-- Order database
SELECT last_value AS seq_current_value FROM order_id_seq;
SELECT COALESCE(MAX(orderid), 0) AS max_migrated_id FROM orders;
```

**Pass criteria:** `seq_current_value > max_migrated_id`. The sequence value should be at least `max_migrated_id + 1000`.

### Check 6: Column Mapping Completeness

**Purpose:** Verify that no column was silently dropped during the migration. Every column in the HSQLDB schema must have a corresponding column in the PostgreSQL schema.

**Procedure:** For each of the 12 migrated tables, compare the column count in PostgreSQL to the expected column count from the HSQLDB schema:

| Table          | Expected Column Count |
|----------------|----------------------:|
| `signon`       |                     2 |
| `account`      |                    12 |
| `profile`      |                     5 |
| `bannerdata`   |                     2 |
| `supplier`     |                     9 |
| `category`     |                     3 |
| `product`      |                     4 |
| `item`         |                    11 |
| `inventory`    |                     3 |
| `orders`       |                    26 |
| `orderstatus`  |                     4 |
| `lineitem`     |                     5 |

```sql
-- Example verification for the orders table
SELECT COUNT(*) AS column_count
FROM information_schema.columns
WHERE table_schema = 'public' AND table_name = 'orders';
-- Expected: 26
```

**Pass criteria:** PostgreSQL column count matches expected count for every table.

### Check 7: Data Type Conversion Spot-Checks

**Purpose:** Verify that data type conversions were applied correctly, with special attention to edge cases.

**Procedure:** Run the following spot-checks:

```sql
-- 1. Boolean-like integer values in profile table
SELECT userid, mylistopt, banneropt FROM profile
WHERE mylistopt NOT IN (0, 1) OR banneropt NOT IN (0, 1);
-- Expected: 0 rows (all values should be 0 or 1)

-- 2. NULL handling: verify NULLs were not converted to empty strings or zeros
SELECT COUNT(*) FROM account WHERE addr2 IS NULL;
-- Should match HSQLDB count of NULL addr2 values

-- 3. Decimal precision: verify monetary values preserved precision
SELECT itemid, listprice, unitcost FROM item
WHERE listprice IS NOT NULL
ORDER BY itemid LIMIT 5;
-- Verify values match source data exactly (e.g., EST-1: listprice=16.50, unitcost=10.00)

-- 4. VARCHAR length: verify no truncation occurred
SELECT MAX(LENGTH(bannername)) FROM bannerdata;
-- Should not exceed 255

-- 5. Date values: verify order dates were preserved
SELECT orderid, orderdate FROM orders ORDER BY orderid LIMIT 5;
-- Compare with source data
```

**Pass criteria:** All spot-checks produce expected results with no data corruption.

### Validation Gate Summary

| # | Check | Status |
|---|-------|--------|
| 1 | Row count match | [ ] PASS / [ ] FAIL |
| 2 | Primary key uniqueness | [ ] PASS / [ ] FAIL |
| 3 | Intra-service FK integrity | [ ] PASS / [ ] FAIL |
| 4 | Cross-service reference integrity | [ ] PASS / [ ] FAIL |
| 5 | Sequence safety | [ ] PASS / [ ] FAIL |
| 6 | Column mapping completeness | [ ] PASS / [ ] FAIL |
| 7 | Data type conversion spot-checks | [ ] PASS / [ ] FAIL |

**Proceed to cutover only when all 7 checks show PASS.**

---

## 6. Service Cutover Procedure

Services are cut over one at a time, in the following order. This order is mandatory — do not skip ahead or reorder.

### Cutover Order Rationale

| Order | Service | Risk Level | Justification |
|-------|---------|------------|---------------|
| 1st | Catalog Service | Lowest | Entirely read-only in normal operation (inventory writes only triggered by Order Service, which is cut over last). Validates the complete infrastructure pattern — Docker, PostgreSQL, API Gateway routing, dual-write — on the simplest service. No dependencies on other bounded contexts. |
| 2nd | Account Service | Medium | Involves write operations (registration, profile updates) but is self-contained within its 4 tables. Has one outbound dependency on Catalog Service (personalization) which is already live from Step 1. Validates JWT authentication. |
| 3rd | Order Service | Highest | Contains the distributed order transaction (Saga pattern), depends on both Catalog Service (inventory reservation) and Account Service (user verification) — both already live from Steps 1 and 2. Requires the Saga orchestrator, externalized cart state, and cross-service coordination. |

---

### Step 1: Catalog Service Cutover

**Prerequisites:**
- [ ] Validation gate (Section 5) passed for Catalog tables
- [ ] Catalog Service deployed and health check passing
- [ ] Dual-write enabled for Catalog bounded context (see Section 7)

**Cutover steps:**

1. **Verify dual-write is operational:**
   ```bash
   # Check service health (dual-write is enabled via application configuration)
   curl -s http://catalog-service:8082/actuator/health | jq '.status'
   # Expected: "UP"
   ```

2. **Switch the routing flag:**
   ```bash
   redis-cli SET routing.flag.catalog-service "microservice"
   ```

3. **Verify traffic is routing to the Catalog Service:**
   ```bash
   # Check API Gateway logs for routing decisions
   # Verify Catalog Service is receiving requests
   curl -s http://api-gateway:8080/api/categories | jq .
   ```

4. **Smoke test — verify responses match monolith behavior:**
   - Browse all 5 categories (FISH, DOGS, REPTILES, CATS, BIRDS)
   - Search for products by keyword
   - View individual product and item details
   - Verify inventory quantities match

5. **Begin 48-hour observation window.** Monitor:
   - Catalog Service error rates and latency
   - Dual-write lag (must stay under 5 seconds)
   - PostgreSQL and HSQLDB row counts remain synchronized
   - No application errors in API Gateway or monolith logs

**Rollback (if needed):**
```bash
redis-cli SET routing.flag.catalog-service "monolith"
```

---

### Step 2: Account Service Cutover

**Prerequisites:**
- [ ] Catalog Service observation window (48 hours) completed with no incidents
- [ ] Validation gate (Section 5) passed for Account tables
- [ ] Account Service deployed and health check passing
- [ ] Dual-write enabled for Account bounded context

**Cutover steps:**

1. **Verify dual-write is operational:**
   ```bash
   # Check service health (dual-write is enabled via application configuration)
   curl -s http://account-service:8081/actuator/health | jq '.status'
   # Expected: "UP"
   ```

2. **Switch the routing flag:**
   ```bash
   redis-cli SET routing.flag.account-service "microservice"
   ```

3. **Verify traffic is routing to the Account Service:**
   ```bash
   curl -s http://api-gateway:8080/api/accounts/j2ee \
     -H "Authorization: Bearer ${JWT_TOKEN}" | jq .
   ```

4. **Smoke test — verify core account operations:**
   - Sign in with existing credentials (`j2ee` / `j2ee`)
   - Verify JWT token is issued and valid
   - View account details
   - Edit account profile
   - Register a new account
   - Verify personalization (myList) from Catalog Service integration

5. **Begin 48-hour observation window.** Monitor:
   - Account Service error rates and latency
   - JWT authentication success/failure rates
   - Dual-write lag
   - No authentication regressions

**Rollback (if needed):**
```bash
redis-cli SET routing.flag.account-service "monolith"
```

---

### Step 3: Order Service Cutover

**Prerequisites:**
- [ ] Account Service observation window (48 hours) completed with no incidents
- [ ] Validation gate (Section 5) passed for Order tables
- [ ] Order Service deployed and health check passing
- [ ] Dual-write enabled for Order bounded context
- [ ] Redis cart state store is operational
- [ ] Saga orchestrator is configured and tested

**Cutover steps:**

1. **Verify dual-write is operational:**
   ```bash
   # Check service health (dual-write is enabled via application configuration)
   curl -s http://order-service:8083/actuator/health | jq '.status'
   # Expected: "UP"
   ```

2. **Switch the routing flag:**
   ```bash
   redis-cli SET routing.flag.order-service "microservice"
   ```

3. **Verify traffic is routing to the Order Service:**
   ```bash
   # Verify cart operations
   curl -s http://api-gateway:8080/api/cart/${SESSION_ID} | jq .

   # Verify order listing
   curl -s http://api-gateway:8080/api/orders?username=j2ee \
     -H "Authorization: Bearer ${JWT_TOKEN}" | jq .
   ```

4. **Smoke test — verify critical order operations:**
   - Add items to cart (externalized via Redis)
   - Update cart quantities
   - Remove items from cart
   - Place a complete order (Saga: create order → reserve inventory → confirm)
   - Verify inventory was decremented in Catalog Service
   - View order history
   - View order details with line items

5. **Saga failure test (staging only):**
   - Attempt to order an item with insufficient inventory
   - Verify the order status is FAILED
   - Verify no inventory was decremented

6. **Begin 48-hour observation window.** Monitor:
   - Order Service error rates and latency
   - Saga success/failure/compensation rates
   - Cart state in Redis
   - Cross-service communication (Order → Catalog, Order → Account)
   - Dual-write lag
   - No data inconsistencies

**Rollback (if needed):**
```bash
redis-cli SET routing.flag.order-service "monolith"
```

---

## 7. Dual-Write Enable/Disable Procedure

The dual-write mechanism ensures that every write operation is reflected in both PostgreSQL (primary) and HSQLDB (secondary) during the coexistence window. This guarantees safe rollback at any point before dual-write is explicitly disabled.

### 7.1 Architecture

```
Write Request → Microservice → PostgreSQL (primary write)
                     ↓
              DualWriteInterceptor → HSQLDB (async secondary write)
                     ↓ (on failure)
              Dead Letter / Retry Log → Alert
```

- **Write direction:** PostgreSQL (primary) → HSQLDB (secondary)
- **Propagation mode:** Asynchronous (non-blocking to the primary write path)
- **Maximum acceptable lag:** 5 seconds
- **Conflict detection:** `last_modified_timestamp` comparison on each row

### 7.2 Enabling Dual-Write

Dual-write must be enabled and verified **before** the routing flag is switched for a service.

**Steps to enable:**

1. **Deploy the DualWriteConfig** in the relevant microservice's configuration:
   ```yaml
   # In the service's application.yml
   dualwrite:
     enabled: true
     hsqldb:
       url: jdbc:hsqldb:hsql://monolith-host:9001/jpetstore
       username: SA
       password: ""
     max-lag-seconds: 5
     retry-attempts: 3
     dead-letter-enabled: true
   ```

2. **Restart the microservice** (or use Spring Cloud Config refresh if available).

3. **Verify dual-write is active:**
   ```bash
   # Check that the service is healthy after enabling dual-write
   curl -s http://<service>:<port>/actuator/health | jq '.status'
   # Expected: "UP"
   # Note: Dual-write status is managed via application configuration properties,
   # not exposed as a separate health indicator component.
   ```

4. **Test dual-write with a write operation:**
   - Perform a write through the microservice (e.g., update an account)
   - Verify the change appears in both PostgreSQL and HSQLDB within 5 seconds
   - Verify the dual-write metrics show successful propagation

### 7.3 Monitoring Dual-Write During Observation Window

During the 48-hour observation window, continuously monitor:

| Metric | Threshold | Action if Exceeded |
|--------|-----------|-------------------|
| Dual-write lag | > 5 seconds | Investigate and resolve; consider rollback if persistent |
| Dual-write failure rate | > 0% | Check dead letter log; investigate root cause |
| Conflict alerts | Any occurrence | Investigate immediately; may indicate routing misconfiguration |
| HSQLDB vs PostgreSQL row count delta | > 0 | Investigate missing writes |

### 7.4 Disabling Dual-Write (Point of No Return)

> **WARNING:** Disabling dual-write is the **point of no return**. After this step, rolling back to the monolith for this service's bounded context is no longer safe without manual data reconciliation.

**Pre-conditions (all must be true):**

- [ ] The 48-hour observation window has passed with **zero** incidents
- [ ] PostgreSQL and HSQLDB row counts match exactly for all tables in this service's bounded context
- [ ] Dual-write lag has been consistently under 5 seconds
- [ ] No conflict alerts were triggered
- [ ] The team has made an explicit, documented decision to proceed past the point of no return

**Steps to disable:**

1. **Final row count verification:**
   ```sql
   -- Compare PostgreSQL and HSQLDB row counts for the service's tables
   -- All must match exactly
   ```

2. **Disable dual-write (runtime configuration — no redeployment required):**
   ```bash
   # Option A: Redis-based runtime config
   redis-cli SET dualwrite.enabled.<service-name> "false"

   # Option B: Spring Cloud Config or actuator endpoint
   curl -X POST http://<service>:<port>/actuator/dualwrite/disable
   ```

3. **Mark HSQLDB tables as read-only** for this service's bounded context:
   ```sql
   -- Execute against HSQLDB (for Catalog Service example)
   SET TABLE category READ ONLY;
   SET TABLE product READ ONLY;
   SET TABLE item READ ONLY;
   SET TABLE inventory READ ONLY;
   SET TABLE supplier READ ONLY;
   ```

4. **Verify dual-write is disabled:**
   ```bash
   curl -s http://<service>:<port>/actuator/health | jq '.components.dualWrite'
   # Expected: {"status": "UP", "details": {"enabled": false}}
   ```

5. **Document the timestamp** of dual-write disablement for this service:
   ```
   Dual-write disabled for <service-name> at: ________________ (UTC)
   Approved by: ________________
   ```

---

## 8. Rollback Procedures

### 8.1 Rollback Decision Matrix

| Current State | Rollback Safety | Procedure |
|--------------|-----------------|-----------|
| Before routing flag switch | Completely safe | No action needed — traffic is still going to monolith |
| After routing flag switch, dual-write active | Safe | Revert routing flag in Redis (instant) |
| After dual-write disabled | **Unsafe** — point of no return | Manual data reconciliation required; avoid this scenario |

### 8.2 Safe Rollback — Before Dual-Write Disabled

When the routing flag has been switched to `"microservice"` but dual-write is still active, rollback is instant and safe:

**Step 1:** Revert the routing flag to the monolith:

```bash
# Catalog Service rollback
redis-cli SET routing.flag.catalog-service "monolith"

# Account Service rollback
redis-cli SET routing.flag.account-service "monolith"

# Order Service rollback
redis-cli SET routing.flag.order-service "monolith"
```

**Step 2:** Verify traffic is now routing to the monolith:
```bash
# Check API Gateway routing logs
# Verify monolith is serving requests
```

**Step 3:** No data rollback is needed. The dual-write mechanism has kept both HSQLDB and PostgreSQL in sync. All writes that went to PostgreSQL during the microservice period were propagated to HSQLDB.

**Step 4:** Investigate the root cause of the issue before attempting cutover again.

### 8.3 Unsafe Rollback — After Dual-Write Disabled

> **This scenario should be avoided at all costs.** The 48-hour observation window exists specifically to prevent reaching this state prematurely.

If rollback is needed after dual-write has been disabled:

1. **Revert the routing flag** to `"monolith"` (same as safe rollback).
2. **Data reconciliation is required:** Writes that occurred after dual-write was disabled exist only in PostgreSQL. These must be manually exported and applied to HSQLDB.
3. **Re-enable HSQLDB table write access:**
   ```sql
   SET TABLE <table_name> READ WRITE;
   ```
4. **Execute data reconciliation scripts** (not automated — requires manual verification).
5. **Re-run the validation gate** before resuming normal monolith operation.

### 8.4 Rollback Testing Requirement

**Before any production cutover**, the rollback procedure must be tested and verified in a staging environment:

1. Deploy the full stack (monolith, microservices, API Gateway, databases, Redis) in staging.
2. Switch a routing flag to `"microservice"`.
3. Perform write operations through the microservice.
4. Verify dual-write propagates to HSQLDB.
5. Switch the routing flag back to `"monolith"`.
6. Verify the monolith reads the data written during the microservice period.
7. Confirm zero data loss.

| Test Case | Expected Result | Actual Result |
|-----------|-----------------|---------------|
| Flag switch to microservice | Traffic routes to microservice | _____________ |
| Write via microservice | Data in PostgreSQL AND HSQLDB | _____________ |
| Flag revert to monolith | Traffic routes to monolith | _____________ |
| Read via monolith after rollback | Data written during microservice period is visible | _____________ |
| No data loss | All rows accounted for | _____________ |

---

## 9. Post-Migration Cleanup

This section applies **only after all three services** have been cut over and dual-write has been disabled for all three bounded contexts.

### 9.1 Final Verification

- [ ] All three routing flags are set to `"microservice"`:
  ```bash
  redis-cli GET routing.flag.catalog-service   # "microservice"
  redis-cli GET routing.flag.account-service   # "microservice"
  redis-cli GET routing.flag.order-service     # "microservice"
  ```
- [ ] Dual-write is disabled for all three services.
- [ ] All three services have passed their respective 48-hour observation windows.
- [ ] No incidents or data inconsistencies have been detected.

### 9.2 HSQLDB Decommission

1. **Mark all remaining HSQLDB tables as read-only** (if not already done during individual service dual-write disablement):
   ```sql
   -- All 13 tables set to read-only
   SET TABLE signon READ ONLY;
   SET TABLE account READ ONLY;
   SET TABLE profile READ ONLY;
   SET TABLE bannerdata READ ONLY;
   SET TABLE category READ ONLY;
   SET TABLE product READ ONLY;
   SET TABLE item READ ONLY;
   SET TABLE inventory READ ONLY;
   SET TABLE supplier READ ONLY;
   SET TABLE orders READ ONLY;
   SET TABLE orderstatus READ ONLY;
   SET TABLE lineitem READ ONLY;
   SET TABLE sequence READ ONLY;
   ```

2. **Take a final HSQLDB backup** (for archival purposes).

3. **Stop the HSQLDB server** after confirming all traffic is served by microservices.

4. **Retain the HSQLDB backup** for a minimum of 90 days (or per organizational data retention policy).

### 9.3 Configuration Cleanup

- [ ] Remove `DualWriteConfig` and `DualWriteInterceptor` from all three microservices.
- [ ] Remove HSQLDB JDBC connection configuration from all service `application.yml` files.
- [ ] Remove the HSQLDB service from `docker-compose.yml`.
- [ ] Remove the monolith service from `docker-compose.yml` (if all traffic is now served by microservices and the API Gateway).
- [ ] Clean up Redis routing flags (optional — they can remain as documentation):
  ```bash
  redis-cli DEL routing.flag.catalog-service
  redis-cli DEL routing.flag.account-service
  redis-cli DEL routing.flag.order-service
  ```

### 9.4 Migration Artifacts

- [ ] Archive the export data directory (`migration/export-data/`).
- [ ] Archive the validation gate results.
- [ ] Archive the dual-write disablement timestamps and approvals.
- [ ] Update the `README.md` to reflect the final architecture (monolith decommissioned).

---

## 10. Critical Rules Reference

These rules govern the entire migration process and may not be overridden by any operational decision.

| # | Rule | Consequence of Violation |
|---|------|--------------------------|
| 1 | **Zero data loss:** Every row in all 13 HSQLDB tables must be verifiably present in the target PostgreSQL databases. | Migration is invalid; must be re-executed. |
| 2 | **No HSQLDB modification before dual-write:** No production data in HSQLDB is modified or deleted before dual-write is active and the validation gate has passed. | Rollback safety is compromised. |
| 3 | **Mandatory validation gate:** The 7-check validation gate must NOT be skipped before any service cutover. | Cutover is blocked; no exceptions. |
| 4 | **48-hour observation window:** Dual-write must NOT be disabled for a service before its post-cutover 48-hour observation window has completed with zero incidents. | Premature point-of-no-return; rollback safety lost. |
| 5 | **Sequence safety:** All PostgreSQL sequence/auto-increment starting values must exceed the maximum migrated ID by a buffer of at least 1000. | ID collisions between legacy and new orders. |
| 6 | **No cross-database access:** No service may query another service's PostgreSQL database directly. All cross-service data access goes through REST APIs. | Architectural violation; coupling reintroduced. |
| 7 | **Idempotent data loading:** Re-running the data load must not create duplicate rows. | Data corruption; validation gate will fail. |
| 8 | **Non-disruptive export:** The HSQLDB export must not modify data or affect application traffic. | Production impact during migration. |
| 9 | **Cutover order:** Services must be cut over in the order Catalog → Account → Order. | Dependency violations; downstream services may fail. |
| 10 | **Rollback testing:** The rollback procedure must be tested in staging before any production cutover. | Untested rollback in production; operational risk. |

---

## Appendix A: Quick Reference Commands

### Routing Flag Commands

```bash
# Check current routing state
redis-cli GET routing.flag.catalog-service
redis-cli GET routing.flag.account-service
redis-cli GET routing.flag.order-service

# Switch to microservice
redis-cli SET routing.flag.catalog-service "microservice"
redis-cli SET routing.flag.account-service "microservice"
redis-cli SET routing.flag.order-service "microservice"

# Emergency rollback to monolith
redis-cli SET routing.flag.catalog-service "monolith"
redis-cli SET routing.flag.account-service "monolith"
redis-cli SET routing.flag.order-service "monolith"
```

### Health Check Commands

```bash
# Service health
curl -s http://account-service:8081/actuator/health | jq .
curl -s http://catalog-service:8082/actuator/health | jq .
curl -s http://order-service:8083/actuator/health | jq .
curl -s http://api-gateway:8080/actuator/health | jq .

# Dual-write status (managed via application configuration, not a separate health indicator)
# Verify dual-write configuration is active by checking application properties:
# spring.dual-write.enabled=true in each service's application.yml
```

### PostgreSQL Connection Commands

```bash
# Connect to each database (use service-specific users from docker-compose.yml)
psql -h postgres-account -p 5432 -U account_user -d jpetstore_account
psql -h postgres-catalog -p 5433 -U catalog_user -d jpetstore_catalog
psql -h postgres-order   -p 5434 -U order_user   -d jpetstore_order
```

## Appendix B: Seed Data Reference

For validation purposes, the following seed data counts are derived from `jpetstore-hsqldb-dataload.sql`:

| Table        | Seed Rows | Key Data Points |
|-------------|----------:|-----------------|
| `sequence`   |         1 | `ordernum` starts at 1000 |
| `signon`     |         2 | Users: `j2ee`, `ACID` |
| `account`    |         2 | Same users with Palo Alto addresses |
| `profile`    |         2 | `j2ee` → DOGS, `ACID` → CATS |
| `bannerdata` |         5 | One banner per category: FISH, CATS, DOGS, REPTILES, BIRDS |
| `category`   |         5 | FISH, DOGS, REPTILES, CATS, BIRDS |
| `product`    |        16 | 4 FISH, 6 DOGS, 2 REPTILES, 2 CATS, 2 BIRDS |
| `supplier`   |         2 | XYZ Pets (ID 1), ABC Pets (ID 2) |
| `item`       |        28 | EST-1 through EST-28, all supplier=1, status=P |
| `inventory`  |        28 | All items start with qty=10000 |
| `orders`     |         0 | No seed orders |
| `orderstatus`|         0 | No seed order statuses |
| `lineitem`   |         0 | No seed line items |
