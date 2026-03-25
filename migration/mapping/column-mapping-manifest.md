# HSQLDB → PostgreSQL Column Mapping Manifest

> **Generated for JPetStore 6 Microservices Decomposition**
> **Version:** 1.0
> **Date:** 2026-03-24

## Purpose

This manifest provides the **authoritative, exhaustive column-level mapping** for all 13 tables in the JPetStore HSQLDB schema as they are decomposed into three independent PostgreSQL databases — one per bounded context:

| Target Database | Bounded Context | Tables |
|----------------|----------------|--------|
| `jpetstore_account` | Account / User Management | `signon`, `account`, `profile`, `bannerdata` |
| `jpetstore_catalog` | Catalog / Inventory | `category`, `product`, `item`, `inventory`, `supplier` |
| `jpetstore_order` | Order / Cart | `orders`, `orderstatus`, `lineitem`, `sequence` |

This document serves as the single source of truth for:

- **Liquibase changelog authors** creating the `001-initial-schema.xml` changesets in each service
- **`SchemaMapper.java`** in the migration module for automated HSQLDB → PostgreSQL type conversion
- **`DataLoader.java`** and **`DataExporter.java`** for column name translation during data migration
- **JPA entity authors** mapping `@Column(name = "...")` annotations in each microservice
- **Data integrity validation** comparing source and target schemas

---

## Table of Contents

1. [Global Data Type Mapping Rules](#1-global-data-type-mapping-rules)
2. [Column Naming Convention](#2-column-naming-convention)
3. [Constraint Preservation Rules](#3-constraint-preservation-rules)
4. [Account Database — `jpetstore_account` (4 Tables, 21 Columns)](#4-account-database--jpetstore_account)
   - 4.1 [signon](#41-table-signon)
   - 4.2 [account](#42-table-account)
   - 4.3 [profile](#43-table-profile)
   - 4.4 [bannerdata](#44-table-bannerdata)
5. [Catalog Database — `jpetstore_catalog` (5 Tables, 30 Columns)](#5-catalog-database--jpetstore_catalog)
   - 5.1 [category](#51-table-category)
   - 5.2 [product](#52-table-product)
   - 5.3 [item](#53-table-item)
   - 5.4 [inventory](#54-table-inventory)
   - 5.5 [supplier](#55-table-supplier)
6. [Order Database — `jpetstore_order` (4 Tables, 36 Columns)](#6-order-database--jpetstore_order)
   - 6.1 [orders](#61-table-orders)
   - 6.2 [orderstatus](#62-table-orderstatus)
   - 6.3 [lineitem](#63-table-lineitem)
   - 6.4 [sequence](#64-table-sequence)
7. [Cross-Service Foreign Key Removal](#7-cross-service-foreign-key-removal)
8. [Index Mapping](#8-index-mapping)
9. [Sequence Replacement](#9-sequence-replacement)
10. [Summary Statistics](#10-summary-statistics)

---

## 1. Global Data Type Mapping Rules

The following data type conversions are applied **globally** across all 13 tables during the HSQLDB → PostgreSQL migration. Precision and scale are preserved exactly.

| HSQLDB Type | PostgreSQL Type | Semantics | Notes |
|-------------|----------------|-----------|-------|
| `VARCHAR(n)` | `varchar(n)` | Variable-length character string | Length constraint `n` is preserved exactly |
| `INT` / `INTEGER` | `integer` | 32-bit signed integer | Standard integer type, identical semantics |
| `DECIMAL(p,s)` | `numeric(p,s)` | Exact numeric with fixed precision and scale | Precision `p` and scale `s` preserved exactly (e.g., `DECIMAL(10,2)` → `numeric(10,2)`) |
| `DATE` (order-related) | `timestamp with time zone` | Date/time with timezone | Applied to `orders.orderdate` and `orderstatus.timestamp` to ensure timezone awareness in the distributed system |

### Timezone Assumptions

- The HSQLDB `DATE` type stores date-only values (no time component).
- In PostgreSQL, `orders.order_date` and `orderstatus.timestamp` are promoted to `timestamp with time zone` to support timezone-aware operations in a distributed microservices environment.
- During data migration, HSQLDB `DATE` values are converted to `timestamp with time zone` by appending `T00:00:00Z` (midnight UTC) to preserve the original date semantics without introducing timezone drift.
- No other columns in the 13-table schema use the `DATE` type; therefore, no `date` → `date` mapping is required.

---

## 2. Column Naming Convention

Column naming follows a **per-service convention** aligned with each service's Liquibase schema:

- **Account Service** and **Catalog Service**: Column names are preserved as **HSQLDB-identical lowercase** (e.g., `userid`, `firstname`, `catid`, `productid`). No snake_case splitting is applied. This minimizes migration complexity for these services where the HSQLDB names are already lowercase and unambiguous.
- **Order Service**: Column names are converted to **`snake_case`** convention (e.g., `orderid` → `order_id`, `billtofirstname` → `bill_to_first_name`). This was applied because the Order Service tables have many long compound column names that benefit from snake_case readability.

| Service | Naming Convention | Example |
|---------|-------------------|---------|
| Account Service | HSQLDB-identical lowercase | `userid` → `userid`, `firstname` → `firstname` |
| Catalog Service | HSQLDB-identical lowercase | `catid` → `catid`, `productid` → `productid` |
| Order Service | snake_case | `orderid` → `order_id`, `billtofirstname` → `bill_to_first_name` |

**Order Service snake_case rules:**

| Naming Pattern | HSQLDB Example | PostgreSQL Result | Rule Applied |
|---------------|---------------|-------------------|-------------|
| Compound word (no separator) | `orderid` | `order_id` | Split at logical word boundary |
| Compound word (no separator) | `billtofirstname` | `bill_to_first_name` | Split at each logical word boundary |
| Special case | `userid` | `username` | Renamed to match application semantics (no FK to account) |
| Short name (already lowercase) | `addr1` | `addr1` | Preserved as-is (already snake_case-compatible) |
| Single word (already lowercase) | `courier` | `courier` | No change needed |

---

## 3. Constraint Preservation Rules

| Constraint Type | Rule | Scope |
|----------------|------|-------|
| **Primary Key (PK)** | Preserved exactly as defined in source schema | All tables within each service database |
| **NOT NULL** | Preserved exactly as defined in source schema | All columns |
| **UNIQUE** | Preserved if present in source schema | All tables |
| **Intra-service Foreign Key** | **Preserved** as database-level constraint | FKs where both tables reside in the same database (e.g., `product.category → category.catid`) |
| **Cross-service Foreign Key** | **Removed** as database constraint; enforced at application layer via REST API calls | FKs where tables reside in different databases (e.g., `lineitem.item_id → item.itemid`, `orders.username → account.userid`) |

---

## 4. Account Database — `jpetstore_account`

**4 tables, 21 columns total**

This database serves the **Account Service** and contains all user identity, authentication, preference, and personalization data.

### 4.1 Table: `signon`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 30–34):
```sql
CREATE TABLE signon (
    username VARCHAR(25) NOT NULL,
    password VARCHAR(25) NOT NULL,
    CONSTRAINT pk_signon PRIMARY KEY (username)
);
```

**Column Mapping (2 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `username` | `VARCHAR(25)` | `username` | `varchar(25)` | NOT NULL | PK (`pk_signon`) | No |
| 2 | `password` | `VARCHAR(25)` | `password` | `varchar(25)` | NOT NULL | — | No |

**Notes:**
- Column names are already lowercase and snake_case-compatible — no renaming required.
- The `password` column stores plaintext passwords in the HSQLDB monolith. The microservice should consider hashing, but this is a data migration concern, not a schema mapping concern — the column type and length are preserved.

---

### 4.2 Table: `account`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 36–50):
```sql
CREATE TABLE account (
    userid    VARCHAR(80) NOT NULL,
    email     VARCHAR(80) NOT NULL,
    firstname VARCHAR(80) NOT NULL,
    lastname  VARCHAR(80) NOT NULL,
    status    VARCHAR(2)  NULL,
    addr1     VARCHAR(80) NOT NULL,
    addr2     VARCHAR(40) NULL,
    city      VARCHAR(80) NOT NULL,
    state     VARCHAR(80) NOT NULL,
    zip       VARCHAR(20) NOT NULL,
    country   VARCHAR(20) NOT NULL,
    phone     VARCHAR(80) NOT NULL,
    CONSTRAINT pk_account PRIMARY KEY (userid)
);
```

**Column Mapping (12 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `userid` | `VARCHAR(80)` | `userid` | `varchar(80)` | NOT NULL | PK (`pk_account`) | No |
| 2 | `email` | `VARCHAR(80)` | `email` | `varchar(80)` | NOT NULL | — | No |
| 3 | `firstname` | `VARCHAR(80)` | `firstname` | `varchar(80)` | NOT NULL | — | No |
| 4 | `lastname` | `VARCHAR(80)` | `lastname` | `varchar(80)` | NOT NULL | — | No |
| 5 | `status` | `VARCHAR(2)` | `status` | `varchar(2)` | NULL | — | No |
| 6 | `addr1` | `VARCHAR(80)` | `addr1` | `varchar(80)` | NOT NULL | — | No |
| 7 | `addr2` | `VARCHAR(40)` | `addr2` | `varchar(40)` | NULL | — | No |
| 8 | `city` | `VARCHAR(80)` | `city` | `varchar(80)` | NOT NULL | — | No |
| 9 | `state` | `VARCHAR(80)` | `state` | `varchar(80)` | NOT NULL | — | No |
| 10 | `zip` | `VARCHAR(20)` | `zip` | `varchar(20)` | NOT NULL | — | No |
| 11 | `country` | `VARCHAR(20)` | `country` | `varchar(20)` | NOT NULL | — | No |
| 12 | `phone` | `VARCHAR(80)` | `phone` | `varchar(80)` | NOT NULL | — | No |

**Renaming Details:**
- No columns renamed — Account Service uses HSQLDB-identical lowercase column names per the per-service naming convention (see [Section 2](#2-column-naming-convention)).

---

### 4.3 Table: `profile`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 52–59):
```sql
CREATE TABLE profile (
    userid      VARCHAR(80) NOT NULL,
    langpref    VARCHAR(80) NOT NULL,
    favcategory VARCHAR(30),
    mylistopt   INT,
    banneropt   INT,
    CONSTRAINT pk_profile PRIMARY KEY (userid)
);
```

**Column Mapping (5 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `userid` | `VARCHAR(80)` | `userid` | `varchar(80)` | NOT NULL | PK (`pk_profile`) | No |
| 2 | `langpref` | `VARCHAR(80)` | `langpref` | `varchar(80)` | NOT NULL | — | No |
| 3 | `favcategory` | `VARCHAR(30)` | `favcategory` | `varchar(30)` | NULL | — | No |
| 4 | `mylistopt` | `INT` | `mylistopt` | `integer` | NULL | — | No |
| 5 | `banneropt` | `INT` | `banneropt` | `integer` | NULL | — | No |

**Renaming Details:**
- No columns renamed — Account Service uses HSQLDB-identical lowercase column names.

**Data Type Changes:**
- `INT` → `integer` (columns 4, 5)

---

### 4.4 Table: `bannerdata`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 61–65):
```sql
CREATE TABLE bannerdata (
    favcategory VARCHAR(80)  NOT NULL,
    bannername  VARCHAR(255) NULL,
    CONSTRAINT pk_bannerdata PRIMARY KEY (favcategory)
);
```

**Column Mapping (2 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `favcategory` | `VARCHAR(80)` | `favcategory` | `varchar(80)` | NOT NULL | PK (`pk_bannerdata`) | No |
| 2 | `bannername` | `VARCHAR(255)` | `bannername` | `varchar(255)` | NULL | — | No |

**Renaming Details:**
- No columns renamed — Account Service uses HSQLDB-identical lowercase column names.

---

## 5. Catalog Database — `jpetstore_catalog`

**5 tables, 30 columns total** (includes 1 added `version` column for optimistic locking)

This database serves the **Catalog Service** and contains all product catalog, inventory, and supplier data.

### 5.1 Table: `category`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 113–118):
```sql
CREATE TABLE category (
    catid VARCHAR(10)  NOT NULL,
    name  VARCHAR(80)  NULL,
    descn VARCHAR(255) NULL,
    CONSTRAINT pk_category PRIMARY KEY (catid)
);
```

**Column Mapping (3 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `catid` | `VARCHAR(10)` | `catid` | `varchar(10)` | NOT NULL | PK (`pk_category`) | No |
| 2 | `name` | `VARCHAR(80)` | `name` | `varchar(80)` | NULL | — | No |
| 3 | `descn` | `VARCHAR(255)` | `descn` | `varchar(255)` | NULL | — | No |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names per the per-service naming convention (see [Section 2](#2-column-naming-convention)).

---

### 5.2 Table: `product`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 120–128):
```sql
CREATE TABLE product (
    productid VARCHAR(10)  NOT NULL,
    category  VARCHAR(10)  NOT NULL,
    name      VARCHAR(80)  NULL,
    descn     VARCHAR(255) NULL,
    CONSTRAINT pk_product PRIMARY KEY (productid),
    CONSTRAINT fk_product_1 FOREIGN KEY (category) REFERENCES category (catid)
);
```

**Indexes** (lines 130–131):
```sql
CREATE INDEX productCat  ON product (category);
CREATE INDEX productName ON product (name);
```

**Column Mapping (4 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `productid` | `VARCHAR(10)` | `productid` | `varchar(10)` | NOT NULL | PK (`pk_product`) | No |
| 2 | `category` | `VARCHAR(10)` | `category` | `varchar(10)` | NOT NULL | FK → `category(catid)` (INTRA-SERVICE, preserved) | No |
| 3 | `name` | `VARCHAR(80)` | `name` | `varchar(80)` | NULL | — | No |
| 4 | `descn` | `VARCHAR(255)` | `descn` | `varchar(255)` | NULL | — | No |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names.

**Foreign Key:**
- `fk_product_1`: `category` → `category(catid)` — **INTRA-SERVICE** (both tables in `jpetstore_catalog`) → **preserved** as database constraint.

**Indexes:**
- `productCat` → `idx_product_category` on `product(category)`
- `productName` → `idx_product_name` on `product(name)`

---

### 5.3 Table: `item`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 133–150):
```sql
CREATE TABLE item (
    itemid    VARCHAR(10)    NOT NULL,
    productid VARCHAR(10)    NOT NULL,
    listprice DECIMAL(10,2)  NULL,
    unitcost  DECIMAL(10,2)  NULL,
    supplier  INT            NULL,
    status    VARCHAR(2)     NULL,
    attr1     VARCHAR(80)    NULL,
    attr2     VARCHAR(80)    NULL,
    attr3     VARCHAR(80)    NULL,
    attr4     VARCHAR(80)    NULL,
    attr5     VARCHAR(80)    NULL,
    CONSTRAINT pk_item PRIMARY KEY (itemid),
    CONSTRAINT fk_item_1 FOREIGN KEY (productid) REFERENCES product (productid),
    CONSTRAINT fk_item_2 FOREIGN KEY (supplier)  REFERENCES supplier (suppid)
);
```

**Index** (line 152):
```sql
CREATE INDEX itemProd ON item (productid);
```

**Column Mapping (11 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `itemid` | `VARCHAR(10)` | `itemid` | `varchar(10)` | NOT NULL | PK (`pk_item`) | No |
| 2 | `productid` | `VARCHAR(10)` | `productid` | `varchar(10)` | NOT NULL | FK → `product(productid)` (INTRA-SERVICE, preserved) | No |
| 3 | `listprice` | `DECIMAL(10,2)` | `listprice` | `numeric(10,2)` | NULL | — | No |
| 4 | `unitcost` | `DECIMAL(10,2)` | `unitcost` | `numeric(10,2)` | NULL | — | No |
| 5 | `supplier` | `INT` | `supplier` | `integer` | NULL | FK → `supplier(suppid)` (INTRA-SERVICE, preserved) | No |
| 6 | `status` | `VARCHAR(2)` | `status` | `varchar(2)` | NULL | — | No |
| 7 | `attr1` | `VARCHAR(80)` | `attr1` | `varchar(80)` | NULL | — | No |
| 8 | `attr2` | `VARCHAR(80)` | `attr2` | `varchar(80)` | NULL | — | No |
| 9 | `attr3` | `VARCHAR(80)` | `attr3` | `varchar(80)` | NULL | — | No |
| 10 | `attr4` | `VARCHAR(80)` | `attr4` | `varchar(80)` | NULL | — | No |
| 11 | `attr5` | `VARCHAR(80)` | `attr5` | `varchar(80)` | NULL | — | No |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names.

**Data Type Changes:**
- `DECIMAL(10,2)` → `numeric(10,2)` (columns 3, 4)
- `INT` → `integer` (column 5)

**Foreign Keys (both INTRA-SERVICE — preserved):**
- `fk_item_1`: `productid` → `product(productid)` — both tables in `jpetstore_catalog`
- `fk_item_2`: `supplier` → `supplier(suppid)` — both tables in `jpetstore_catalog`

**Index:**
- `itemProd` → `idx_item_productid` on `item(productid)`

---

### 5.4 Table: `inventory`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 154–158):
```sql
CREATE TABLE inventory (
    itemid VARCHAR(10) NOT NULL,
    qty    INT         NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (itemid)
);
```

**Column Mapping (2 migrated columns + 1 added column = 3 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed | Migration Note |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|---------------|
| 1 | `itemid` | `VARCHAR(10)` | `itemid` | `varchar(10)` | NOT NULL | PK (`pk_inventory`) | No | Direct migration |
| 2 | `qty` | `INT` | `qty` | `integer` | NOT NULL | — | No | Direct migration |
| 3 | *(new)* | *(n/a)* | `version` | `integer` | NOT NULL (default 0) | — | N/A | **Added column** — JPA `@Version` for optimistic locking |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names.

**Data Type Changes:**
- `INT` → `integer` (column 2)

**Added Column:**
- `version` (`integer`, NOT NULL, DEFAULT 0) — Required by the Catalog Service's `InventoryService` for optimistic locking via JPA `@Version` annotation. This column does not exist in the HSQLDB source schema and is populated with `0` for all migrated rows.

---

### 5.5 Table: `supplier`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 17–28):
```sql
CREATE TABLE supplier (
    suppid INT         NOT NULL,
    name   VARCHAR(80) NULL,
    status VARCHAR(2)  NOT NULL,
    addr1  VARCHAR(80) NULL,
    addr2  VARCHAR(80) NULL,
    city   VARCHAR(80) NULL,
    state  VARCHAR(80) NULL,
    zip    VARCHAR(5)  NULL,
    phone  VARCHAR(80) NULL,
    CONSTRAINT pk_supplier PRIMARY KEY (suppid)
);
```

**Column Mapping (9 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `suppid` | `INT` | `suppid` | `integer` | NOT NULL | PK (`pk_supplier`) | No |
| 2 | `name` | `VARCHAR(80)` | `name` | `varchar(80)` | NULL | — | No |
| 3 | `status` | `VARCHAR(2)` | `status` | `varchar(2)` | NOT NULL | — | No |
| 4 | `addr1` | `VARCHAR(80)` | `addr1` | `varchar(80)` | NULL | — | No |
| 5 | `addr2` | `VARCHAR(80)` | `addr2` | `varchar(80)` | NULL | — | No |
| 6 | `city` | `VARCHAR(80)` | `city` | `varchar(80)` | NULL | — | No |
| 7 | `state` | `VARCHAR(80)` | `state` | `varchar(80)` | NULL | — | No |
| 8 | `zip` | `VARCHAR(5)` | `zip` | `varchar(5)` | NULL | — | No |
| 9 | `phone` | `VARCHAR(80)` | `phone` | `varchar(80)` | NULL | — | No |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names.

**Data Type Changes:**
- `INT` → `integer` (column 1)

---

## 6. Order Database — `jpetstore_order`

**4 tables, 36 columns total**

This database serves the **Order Service** and contains all order, order status, line item, and sequence data.

### 6.1 Table: `orders`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 67–94):
```sql
CREATE TABLE orders (
    orderid          INT            NOT NULL,
    userid           VARCHAR(80)    NOT NULL,
    orderdate        DATE           NOT NULL,
    shipaddr1        VARCHAR(80)    NOT NULL,
    shipaddr2        VARCHAR(80)    NULL,
    shipcity         VARCHAR(80)    NOT NULL,
    shipstate        VARCHAR(80)    NOT NULL,
    shipzip          VARCHAR(20)    NOT NULL,
    shipcountry      VARCHAR(20)    NOT NULL,
    billaddr1        VARCHAR(80)    NOT NULL,
    billaddr2        VARCHAR(80)    NULL,
    billcity         VARCHAR(80)    NOT NULL,
    billstate        VARCHAR(80)    NOT NULL,
    billzip          VARCHAR(20)    NOT NULL,
    billcountry      VARCHAR(20)    NOT NULL,
    courier          VARCHAR(80)    NOT NULL,
    totalprice       DECIMAL(10,2)  NOT NULL,
    billtofirstname  VARCHAR(80)    NOT NULL,
    billtolastname   VARCHAR(80)    NOT NULL,
    shiptofirstname  VARCHAR(80)    NOT NULL,
    shiptolastname   VARCHAR(80)    NOT NULL,
    creditcard       VARCHAR(80)    NOT NULL,
    exprdate         VARCHAR(7)     NOT NULL,
    cardtype         VARCHAR(80)    NOT NULL,
    locale           VARCHAR(80)    NOT NULL,
    CONSTRAINT pk_orders PRIMARY KEY (orderid)
);
```

**Column Mapping (25 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `orderid` | `INT` | `order_id` | `integer` | NOT NULL | PK (`pk_orders`), generated by sequence `order_id_seq` | Yes |
| 2 | `userid` | `VARCHAR(80)` | `username` | `varchar(80)` | NOT NULL | ⚠️ Cross-service ref → `account.userid` — **NO FK** | Yes (renamed) |
| 3 | `orderdate` | `DATE` | `order_date` | `timestamp with time zone` | NOT NULL | — | Yes |
| 4 | `shipaddr1` | `VARCHAR(80)` | `ship_addr1` | `varchar(80)` | NOT NULL | — | Yes |
| 5 | `shipaddr2` | `VARCHAR(80)` | `ship_addr2` | `varchar(80)` | NULL | — | Yes |
| 6 | `shipcity` | `VARCHAR(80)` | `ship_city` | `varchar(80)` | NOT NULL | — | Yes |
| 7 | `shipstate` | `VARCHAR(80)` | `ship_state` | `varchar(80)` | NOT NULL | — | Yes |
| 8 | `shipzip` | `VARCHAR(20)` | `ship_zip` | `varchar(20)` | NOT NULL | — | Yes |
| 9 | `shipcountry` | `VARCHAR(20)` | `ship_country` | `varchar(20)` | NOT NULL | — | Yes |
| 10 | `billaddr1` | `VARCHAR(80)` | `bill_addr1` | `varchar(80)` | NOT NULL | — | Yes |
| 11 | `billaddr2` | `VARCHAR(80)` | `bill_addr2` | `varchar(80)` | NULL | — | Yes |
| 12 | `billcity` | `VARCHAR(80)` | `bill_city` | `varchar(80)` | NOT NULL | — | Yes |
| 13 | `billstate` | `VARCHAR(80)` | `bill_state` | `varchar(80)` | NOT NULL | — | Yes |
| 14 | `billzip` | `VARCHAR(20)` | `bill_zip` | `varchar(20)` | NOT NULL | — | Yes |
| 15 | `billcountry` | `VARCHAR(20)` | `bill_country` | `varchar(20)` | NOT NULL | — | Yes |
| 16 | `courier` | `VARCHAR(80)` | `courier` | `varchar(80)` | NOT NULL | — | No |
| 17 | `totalprice` | `DECIMAL(10,2)` | `total_price` | `numeric(10,2)` | NOT NULL | — | Yes |
| 18 | `billtofirstname` | `VARCHAR(80)` | `bill_to_first_name` | `varchar(80)` | NOT NULL | — | Yes |
| 19 | `billtolastname` | `VARCHAR(80)` | `bill_to_last_name` | `varchar(80)` | NOT NULL | — | Yes |
| 20 | `shiptofirstname` | `VARCHAR(80)` | `ship_to_first_name` | `varchar(80)` | NOT NULL | — | Yes |
| 21 | `shiptolastname` | `VARCHAR(80)` | `ship_to_last_name` | `varchar(80)` | NOT NULL | — | Yes |
| 22 | `creditcard` | `VARCHAR(80)` | `credit_card` | `varchar(80)` | NOT NULL | — | Yes |
| 23 | `exprdate` | `VARCHAR(7)` | `expr_date` | `varchar(7)` | NOT NULL | — | Yes |
| 24 | `cardtype` | `VARCHAR(80)` | `card_type` | `varchar(80)` | NOT NULL | — | Yes |
| 25 | `locale` | `VARCHAR(80)` | `locale` | `varchar(80)` | NOT NULL | — | No |

**Renaming Details:**
- `orderid` → `order_id`
- `userid` → `username` — renamed to match application semantics (stores username, no FK to account table)
- `orderdate` → `order_date`
- `shipaddr1` → `ship_addr1`, `shipaddr2` → `ship_addr2`
- `shipcity` → `ship_city`, `shipstate` → `ship_state`, `shipzip` → `ship_zip`, `shipcountry` → `ship_country`
- `billaddr1` → `bill_addr1`, `billaddr2` → `bill_addr2`
- `billcity` → `bill_city`, `billstate` → `bill_state`, `billzip` → `bill_zip`, `billcountry` → `bill_country`
- `totalprice` → `total_price`
- `billtofirstname` → `bill_to_first_name`, `billtolastname` → `bill_to_last_name`
- `shiptofirstname` → `ship_to_first_name`, `shiptolastname` → `ship_to_last_name`
- `creditcard` → `credit_card`
- `exprdate` → `expr_date`
- `cardtype` → `card_type`
- `courier`, `locale` — already lowercase, preserved as-is

**Data Type Changes:**
- `INT` → `integer` (column 1)
- `DATE` → `timestamp with time zone` (column 3) — see [Timezone Assumptions](#timezone-assumptions)
- `DECIMAL(10,2)` → `numeric(10,2)` (column 17)

**Primary Key Generation:**
- The `order_id` PK is generated by PostgreSQL native sequence `order_id_seq`, replacing the HSQLDB `sequence` table's row `('ordernum', ...)`. See [Section 9: Sequence Replacement](#9-sequence-replacement) for details.

**Cross-Service Reference:**
- `username` references `account.userid` in the Account database — **NO foreign key constraint** in PostgreSQL. Referential integrity is enforced at the application layer by the Order Service calling `GET /api/accounts/{username}` on the Account Service before order creation.

---

### 6.2 Table: `orderstatus`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 96–102):
```sql
CREATE TABLE orderstatus (
    orderid   INT        NOT NULL,
    linenum   INT        NOT NULL,
    timestamp DATE       NOT NULL,
    status    VARCHAR(2) NOT NULL,
    CONSTRAINT pk_orderstatus PRIMARY KEY (orderid, linenum)
);
```

**Column Mapping (4 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `orderid` | `INT` | `order_id` | `integer` | NOT NULL | Composite PK (part 1) | Yes |
| 2 | `linenum` | `INT` | `line_num` | `integer` | NOT NULL | Composite PK (part 2) | Yes |
| 3 | `timestamp` | `DATE` | `timestamp` | `timestamp with time zone` | NOT NULL | — | No |
| 4 | `status` | `VARCHAR(2)` | `status` | `varchar(2)` | NOT NULL | — | No |

**Renaming Details:**
- `orderid` → `order_id`
- `linenum` → `line_num`
- `timestamp`, `status` — preserved as-is

**Data Type Changes:**
- `INT` → `integer` (columns 1, 2)
- `DATE` → `timestamp with time zone` (column 3) — see [Timezone Assumptions](#timezone-assumptions)

**Primary Key:**
- Composite PK: (`order_id`, `line_num`)

---

### 6.3 Table: `lineitem`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 104–111):
```sql
CREATE TABLE lineitem (
    orderid   INT            NOT NULL,
    linenum   INT            NOT NULL,
    itemid    VARCHAR(10)    NOT NULL,
    quantity  INT            NOT NULL,
    unitprice DECIMAL(10,2)  NOT NULL,
    CONSTRAINT pk_lineitem PRIMARY KEY (orderid, linenum)
);
```

**Column Mapping (5 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `orderid` | `INT` | `order_id` | `integer` | NOT NULL | Composite PK (part 1) | Yes |
| 2 | `linenum` | `INT` | `line_num` | `integer` | NOT NULL | Composite PK (part 2) | Yes |
| 3 | `itemid` | `VARCHAR(10)` | `item_id` | `varchar(10)` | NOT NULL | ⚠️ Cross-service ref → `item.item_id` — **NO FK** | Yes |
| 4 | `quantity` | `INT` | `quantity` | `integer` | NOT NULL | — | No |
| 5 | `unitprice` | `DECIMAL(10,2)` | `unit_price` | `numeric(10,2)` | NOT NULL | — | Yes |

**Renaming Details:**
- `orderid` → `order_id`
- `linenum` → `line_num`
- `itemid` → `item_id`
- `unitprice` → `unit_price`
- `quantity` — already lowercase, preserved as-is

**Data Type Changes:**
- `INT` → `integer` (columns 1, 2, 4)
- `DECIMAL(10,2)` → `numeric(10,2)` (column 5)

**Primary Key:**
- Composite PK: (`order_id`, `line_num`)

**Cross-Service Reference:**
- `item_id` references `item.item_id` in the Catalog database — **NO foreign key constraint** in PostgreSQL. Referential integrity is enforced at the application layer by the Order Service calling `GET /api/items/{id}` on the Catalog Service during order creation.

---

### 6.4 Table: `sequence`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 160–165):
```sql
CREATE TABLE sequence (
    name   VARCHAR(30) NOT NULL,
    nextid INT         NOT NULL,
    CONSTRAINT pk_sequence PRIMARY KEY (name)
);
```

**Column Mapping (2 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `name` | `VARCHAR(30)` | `name` | `varchar(30)` | NOT NULL | PK (`pk_sequence`) | No |
| 2 | `nextid` | `INT` | `nextid` | `integer` | NOT NULL | — | No |

**Renaming Details:**
- No columns renamed — `sequence` table is a deprecated reference table (see note below) and uses HSQLDB-identical names.

**Data Type Changes:**
- `INT` → `integer` (column 2)

> **⚠️ IMPORTANT — DEPRECATED TABLE**
>
> This table is being **replaced** by PostgreSQL native sequences in the Order Service. The HSQLDB `sequence` table row `('ordernum', <nextid>)` is superseded by the PostgreSQL sequence `order_id_seq`. The table is migrated to PostgreSQL for **reference and audit purposes only** — the application code in the Order Service no longer reads from or writes to this table. See [Section 9: Sequence Replacement](#9-sequence-replacement) for full details.

---

## 7. Cross-Service Foreign Key Removal

The following two logical foreign key relationships span different bounded contexts. In the monolith's single HSQLDB database, these were implicit logical references (no explicit `FOREIGN KEY` constraints existed in the DDL). In the decomposed PostgreSQL databases, these references **cannot** be enforced as database constraints because the referenced tables reside in different databases.

### 7.1 `lineitem.itemid` → `item.itemid` (Order DB → Catalog DB)

| Property | Value |
|----------|-------|
| **Source (HSQLDB)** | `lineitem.itemid` references `item.itemid` — no explicit FK constraint in DDL |
| **Target (PostgreSQL)** | `lineitem.item_id` (Order DB, snake_case) references `item.itemid` (Catalog DB, HSQLDB-identical) — stored as plain `varchar(10)` field with **NO FK constraint** |
| **Source Database** | `jpetstore_order` (Order Service) |
| **Target Database** | `jpetstore_catalog` (Catalog Service) |
| **Application-Layer Enforcement** | Order Service validates item existence via synchronous REST call: `GET /api/items/{id}` on Catalog Service before inserting line items |
| **Failure Behavior** | If Catalog Service returns 404 or is unavailable, the order creation fails with an appropriate error — no orphaned line items are created |

### 7.2 `orders.userid` → `account.userid` (Order DB → Account DB)

| Property | Value |
|----------|-------|
| **Source (HSQLDB)** | `orders.userid` references `account.userid` — no explicit FK constraint in DDL |
| **Target (PostgreSQL)** | `orders.username` (Order DB, renamed) references `account.userid` (Account DB, HSQLDB-identical) — stored as plain `varchar(80)` field with **NO FK constraint** |
| **Source Database** | `jpetstore_order` (Order Service) |
| **Target Database** | `jpetstore_account` (Account Service) |
| **Application-Layer Enforcement** | Order Service validates user existence via synchronous REST call: `GET /api/accounts/{username}` on Account Service before creating an order |
| **Failure Behavior** | If Account Service returns 404 or is unavailable, the order creation fails with an appropriate error — no orphaned orders are created |

---

## 8. Index Mapping

The HSQLDB schema defines 3 indexes. All are on tables in the Catalog database and are preserved in PostgreSQL with snake_case naming.

| # | HSQLDB Index Name | Table | HSQLDB Column(s) | PostgreSQL Index Name | PostgreSQL Column(s) | Database |
|---|-------------------|-------|-------------------|----------------------|---------------------|----------|
| 1 | `productCat` | `product` | `category` | `idx_product_category` | `category` | `jpetstore_catalog` |
| 2 | `productName` | `product` | `name` | `idx_product_name` | `name` | `jpetstore_catalog` |
| 3 | `itemProd` | `item` | `productid` | `idx_item_productid` | `productid` | `jpetstore_catalog` |

**Notes:**
- Index names are converted to `idx_<table>_<column>` convention for consistency.
- All indexes are non-unique, single-column B-tree indexes — same as the HSQLDB originals.
- Catalog Service uses HSQLDB-identical column names, so index column references are unchanged (e.g., `productid` remains `productid`).

---

## 9. Sequence Replacement

### HSQLDB Sequence Table (Being Replaced)

The HSQLDB `sequence` table contains rows that provide auto-incrementing IDs via a non-thread-safe read-then-update pattern in `OrderService.getNextId()`:

```
('ordernum', 1000)
('linenum', 1000)
```

### PostgreSQL Native Sequence

The `sequence` table's functionality is **replaced** by PostgreSQL native sequences, which provide atomic, thread-safe, gap-free-optional ID generation:

| HSQLDB Sequence Row | PostgreSQL Replacement | Database | Starting Value Rule |
|---------------------|----------------------|----------|---------------------|
| `('ordernum', <nextid>)` | `CREATE SEQUENCE order_id_seq START WITH <value>;` | `jpetstore_order` | `MAX(migrated order_id) + 1000` |

**Key Rules:**

1. **Starting Value Safety**: The PostgreSQL sequence `order_id_seq` must start at a value **greater than** the maximum `order_id` present in the migrated data, **plus a buffer of 1000**. This guarantees zero ID collisions between legacy migrated orders and new orders created by the Order Service.

2. **No Other Sequences Required**:
   - Account Service: Uses `username` (VARCHAR) as primary key — no integer sequence needed.
   - Catalog Service: Uses string-based IDs (`catid`, `productid`, `itemid` are all VARCHAR, preserved as HSQLDB-identical names) — no integer sequence needed.

3. **Thread Safety**: The PostgreSQL `NEXTVAL('order_id_seq')` function is fully atomic and thread-safe, eliminating the race condition present in the HSQLDB `getNextId()` read-then-update pattern.

4. **JPA Integration**: The Order entity uses `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_seq")` with `@SequenceGenerator(name = "order_seq", sequenceName = "order_id_seq")`.

5. **Decommission**: The `sequence` table is migrated for audit reference but is not used by the application. It can be dropped after the Order Service cutover observation window (48 hours minimum) has passed with no incidents.

---

## 10. Summary Statistics

### Table Distribution

| Database | Bounded Context | Table Count | Column Count |
|----------|----------------|-------------|-------------|
| `jpetstore_account` | Account / User Management | 4 | 21 |
| `jpetstore_catalog` | Catalog / Inventory | 5 | 30 (includes 1 added `version` column) |
| `jpetstore_order` | Order / Cart | 4 | 36 |
| **Total** | | **13** | **87** (86 migrated + 1 added) |

### Data Type Conversion Summary

| HSQLDB Type | PostgreSQL Type | Occurrences |
|-------------|----------------|-------------|
| `VARCHAR(n)` | `varchar(n)` | 68 columns |
| `INT` | `integer` | 16 columns |
| `DECIMAL(10,2)` | `numeric(10,2)` | 4 columns |
| `DATE` | `timestamp with time zone` | 2 columns (`orders.order_date`, `orderstatus.timestamp`) |

### Column Renaming Summary

| Category | Count | Examples |
|----------|-------|---------|
| Order Service columns renamed to snake_case | 22 | `orderid` → `order_id`, `billtofirstname` → `bill_to_first_name` |
| Order Service columns renamed (semantic) | 1 | `userid` → `username` |
| Account/Catalog columns preserved (HSQLDB-identical) | 63 | `userid`, `firstname`, `catid`, `productid`, `email`, `status` |
| Columns added (not in HSQLDB) | 1 | `inventory.version` for optimistic locking |

### Constraint Summary

| Constraint Type | Count | Details |
|----------------|-------|---------|
| Primary Keys preserved | 13 | One per table (2 composite PKs: `orderstatus`, `lineitem`) |
| Intra-service FKs preserved | 3 | `product.category → category.catid`, `item.productid → product.productid`, `item.supplier → supplier.suppid` |
| Cross-service FKs removed | 2 | `orders.username → account.userid`, `lineitem.item_id → item.itemid` |
| Indexes preserved | 3 | `idx_product_category`, `idx_product_name`, `idx_item_productid` |
| Sequences added | 1 | `order_id_seq` (replaces `sequence` table) |
