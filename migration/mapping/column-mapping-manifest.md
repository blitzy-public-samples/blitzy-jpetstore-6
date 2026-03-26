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
| Special case — retained | `userid` | `userid` | Retains original HSQLDB column name for migration data consistency (Liquibase comment: "Retains original HSQLDB column name") |
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
| **Cross-service Foreign Key** | **Removed** as database constraint; enforced at application layer via REST API calls | FKs where tables reside in different databases (e.g., `lineitem.item_id → item.itemid`, `orders.userid → account.userid`) |

---

## 4. Account Database — `jpetstore_account`

**4 tables, 21 columns total**

This database serves the **Account Service** and contains all user identity, authentication, preference, and personalization data.

### 4.1 Table: `signon`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 30–34):
```sql
create table signon (
    username varchar(25) not null,
    password varchar(25) not null,
    constraint pk_signon primary key (username)
);
```

**Column Mapping (2 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `username` | `VARCHAR(25)` | `username` | `varchar(25)` | not null | PK (`pk_signon`) | No |
| 2 | `password` | `VARCHAR(25)` | `password` | `varchar(25)` | not null | — | No |

**Notes:**
- Column names are already lowercase and snake_case-compatible — no renaming required.
- The `password` column stores plaintext passwords in the HSQLDB monolith. The microservice should consider hashing, but this is a data migration concern, not a schema mapping concern — the column type and length are preserved.

---

### 4.2 Table: `account`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 36–50):
```sql
create table account (
    userid   varchar(80) not null,
    email    varchar(80) not null,
    firstname varchar(80) not null,
    lastname varchar(80) not null,
    status   varchar(2)  null,
    addr1    varchar(80) not null,
    addr2    varchar(40) null,
    city     varchar(80) not null,
    state    varchar(80) not null,
    zip      varchar(20) not null,
    country  varchar(20) not null,
    phone    varchar(80) not null,
    constraint pk_account primary key (userid)
);
```

**Column Mapping (12 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `userid` | `VARCHAR(80)` | `userid` | `varchar(80)` | not null | PK (`pk_account`) | No |
| 2 | `email` | `VARCHAR(80)` | `email` | `varchar(80)` | not null | — | No |
| 3 | `firstname` | `VARCHAR(80)` | `firstname` | `varchar(80)` | not null | — | No |
| 4 | `lastname` | `VARCHAR(80)` | `lastname` | `varchar(80)` | not null | — | No |
| 5 | `status` | `VARCHAR(2)` | `status` | `varchar(2)` | null | — | No |
| 6 | `addr1` | `VARCHAR(80)` | `addr1` | `varchar(80)` | not null | — | No |
| 7 | `addr2` | `VARCHAR(40)` | `addr2` | `varchar(40)` | null | — | No |
| 8 | `city` | `VARCHAR(80)` | `city` | `varchar(80)` | not null | — | No |
| 9 | `state` | `VARCHAR(80)` | `state` | `varchar(80)` | not null | — | No |
| 10 | `zip` | `VARCHAR(20)` | `zip` | `varchar(20)` | not null | — | No |
| 11 | `country` | `VARCHAR(20)` | `country` | `varchar(20)` | not null | — | No |
| 12 | `phone` | `VARCHAR(80)` | `phone` | `varchar(80)` | not null | — | No |

**Renaming Details:**
- No columns renamed — Account Service uses HSQLDB-identical lowercase column names per the per-service naming convention (see [Section 2](#2-column-naming-convention)).

---

### 4.3 Table: `profile`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 52–59):
```sql
create table profile (
    userid     varchar(80) not null,
    langpref   varchar(80) not null,
    favcategory varchar(30),
    mylistopt  int,
    banneropt  int,
    constraint pk_profile primary key (userid)
);
```

**Column Mapping (5 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `userid` | `VARCHAR(80)` | `userid` | `varchar(80)` | not null | PK (`pk_profile`) | No |
| 2 | `langpref` | `VARCHAR(80)` | `langpref` | `varchar(80)` | not null | — | No |
| 3 | `favcategory` | `VARCHAR(30)` | `favcategory` | `varchar(30)` | null | — | No |
| 4 | `mylistopt` | `INT` | `mylistopt` | `boolean` | null | — | No (type changed) |
| 5 | `banneropt` | `INT` | `banneropt` | `boolean` | null | — | No (type changed) |

**Renaming Details:**
- No columns renamed — Account Service uses HSQLDB-identical lowercase column names.

**Data Type Changes:**
- `INT` → `boolean` (columns 4, 5) — HSQLDB stores boolean preferences as integer (0/1); PostgreSQL uses native `boolean` type. During migration, `0` → `false`, non-zero → `true`.

---

### 4.4 Table: `bannerdata`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 61–65):
```sql
create table bannerdata (
    favcategory varchar(80)  not null,
    bannername varchar(255) null,
    constraint pk_bannerdata primary key (favcategory)
);
```

**Column Mapping (2 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `favcategory` | `VARCHAR(80)` | `favcategory` | `varchar(80)` | not null | PK (`pk_bannerdata`) | No |
| 2 | `bannername` | `VARCHAR(255)` | `bannername` | `varchar(255)` | null | — | No |

**Renaming Details:**
- No columns renamed — Account Service uses HSQLDB-identical lowercase column names.

---

## 5. Catalog Database — `jpetstore_catalog`

**5 tables, 30 columns total** (includes 1 added `version` column for optimistic locking)

This database serves the **Catalog Service** and contains all product catalog, inventory, and supplier data.

### 5.1 Table: `category`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 113–118):
```sql
create table category (
    catid varchar(10)  not null,
    name varchar(80)  null,
    descn varchar(255) null,
    constraint pk_category primary key (catid)
);
```

**Column Mapping (3 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `catid` | `VARCHAR(10)` | `catid` | `varchar(10)` | not null | PK (`pk_category`) | No |
| 2 | `name` | `VARCHAR(80)` | `name` | `varchar(80)` | null | — | No |
| 3 | `descn` | `VARCHAR(255)` | `descn` | `varchar(255)` | null | — | No |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names per the per-service naming convention (see [Section 2](#2-column-naming-convention)).

---

### 5.2 Table: `product`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 120–128):
```sql
create table product (
    productid varchar(10)  not null,
    category varchar(10)  not null,
    name     varchar(80)  null,
    descn    varchar(255) null,
    constraint pk_product primary key (productid),
    constraint fk_product_1 foreign key (category) references category (catid)
);
```

**Indexes** (lines 130–131):
```sql
create index productCat  ON product (category);
create index productName ON product (name);
```

**Column Mapping (4 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `productid` | `VARCHAR(10)` | `productid` | `varchar(10)` | not null | PK (`pk_product`) | No |
| 2 | `category` | `VARCHAR(10)` | `category` | `varchar(10)` | not null | FK → `category(catid)` (INTRA-SERVICE, preserved) | No |
| 3 | `name` | `VARCHAR(80)` | `name` | `varchar(80)` | null | — | No |
| 4 | `descn` | `VARCHAR(255)` | `descn` | `varchar(255)` | null | — | No |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names.

**Foreign Key:**
- `fk_product_1`: `category` → `category(catid)` — **INTRA-SERVICE** (both tables in `jpetstore_catalog`) → **preserved** as database constraint.

**Indexes:**
- `productCat` on `product(category)` — retained as-is
- `productName` on `product(name)` — retained as-is

---

### 5.3 Table: `item`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 133–150):
```sql
create table item (
    itemid   varchar(10)    not null,
    productid varchar(10)    not null,
    listprice decimal(10,2)  null,
    unitcost  decimal(10,2)  null,
    supplier int            null,
    status   varchar(2)     null,
    attr1    varchar(80)    null,
    attr2    varchar(80)    null,
    attr3    varchar(80)    null,
    attr4    varchar(80)    null,
    attr5    varchar(80)    null,
    constraint pk_item primary key (itemid),
    constraint fk_item_1 foreign key (productid) references product (productid),
    constraint fk_item_2 foreign key (supplier)  references supplier (suppid)
);
```

**Index** (line 152):
```sql
create index itemProd ON item (productid);
```

**Column Mapping (11 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `itemid` | `VARCHAR(10)` | `itemid` | `varchar(10)` | not null | PK (`pk_item`) | No |
| 2 | `productid` | `VARCHAR(10)` | `productid` | `varchar(10)` | not null | FK → `product(productid)` (INTRA-SERVICE, preserved) | No |
| 3 | `listprice` | `DECIMAL(10,2)` | `listprice` | `numeric(10,2)` | null | — | No |
| 4 | `unitcost` | `DECIMAL(10,2)` | `unitcost` | `numeric(10,2)` | null | — | No |
| 5 | `supplier` | `INT` | `supplier` | `integer` | null | FK → `supplier(suppid)` (INTRA-SERVICE, preserved) | No |
| 6 | `status` | `VARCHAR(2)` | `status` | `varchar(2)` | null | — | No |
| 7 | `attr1` | `VARCHAR(80)` | `attr1` | `varchar(80)` | null | — | No |
| 8 | `attr2` | `VARCHAR(80)` | `attr2` | `varchar(80)` | null | — | No |
| 9 | `attr3` | `VARCHAR(80)` | `attr3` | `varchar(80)` | null | — | No |
| 10 | `attr4` | `VARCHAR(80)` | `attr4` | `varchar(80)` | null | — | No |
| 11 | `attr5` | `VARCHAR(80)` | `attr5` | `varchar(80)` | null | — | No |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names.

**Data Type Changes:**
- `DECIMAL(10,2)` → `numeric(10,2)` (columns 3, 4)
- `INT` → `integer` (column 5)

**Foreign Keys (both INTRA-SERVICE — preserved):**
- `fk_item_1`: `productid` → `product(productid)` — both tables in `jpetstore_catalog`
- `fk_item_2`: `supplier` → `supplier(suppid)` — both tables in `jpetstore_catalog`

**Index:**
- `itemProd` on `item(productid)` — retained as-is

---

### 5.4 Table: `inventory`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 154–158):
```sql
create table inventory (
    itemid varchar(10) not null,
    qty   int         not null,
    constraint pk_inventory primary key (itemid)
);
```

**Column Mapping (2 migrated columns + 1 added column = 3 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed | Migration Note |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|---------------|
| 1 | `itemid` | `VARCHAR(10)` | `itemid` | `varchar(10)` | not null | PK (`pk_inventory`) | No | Direct migration |
| 2 | `qty` | `INT` | `qty` | `integer` | not null | — | No | Direct migration |
| 3 | *(new)* | *(n/a)* | `version` | `integer` | not null (default 0) | — | N/A | **Added column** — JPA `@Version` for optimistic locking |

**Renaming Details:**
- No columns renamed — Catalog Service uses HSQLDB-identical lowercase column names.

**Data Type Changes:**
- `INT` → `integer` (column 2)

**Added Column:**
- `version` (`integer`, not null, DEFAULT 0) — Required by the Catalog Service's `InventoryService` for optimistic locking via JPA `@Version` annotation. This column does not exist in the HSQLDB source schema and is populated with `0` for all migrated rows.

---

### 5.5 Table: `supplier`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 17–28):
```sql
create table supplier (
    suppid int         not null,
    name  varchar(80) null,
    status varchar(2)  not null,
    addr1 varchar(80) null,
    addr2 varchar(80) null,
    city  varchar(80) null,
    state varchar(80) null,
    zip   varchar(5)  null,
    phone varchar(80) null,
    constraint pk_supplier primary key (suppid)
);
```

**Column Mapping (9 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `suppid` | `INT` | `suppid` | `integer` | not null | PK (`pk_supplier`) | No |
| 2 | `name` | `VARCHAR(80)` | `name` | `varchar(80)` | null | — | No |
| 3 | `status` | `VARCHAR(2)` | `status` | `varchar(2)` | not null | — | No |
| 4 | `addr1` | `VARCHAR(80)` | `addr1` | `varchar(80)` | null | — | No |
| 5 | `addr2` | `VARCHAR(80)` | `addr2` | `varchar(80)` | null | — | No |
| 6 | `city` | `VARCHAR(80)` | `city` | `varchar(80)` | null | — | No |
| 7 | `state` | `VARCHAR(80)` | `state` | `varchar(80)` | null | — | No |
| 8 | `zip` | `VARCHAR(5)` | `zip` | `varchar(5)` | null | — | No |
| 9 | `phone` | `VARCHAR(80)` | `phone` | `varchar(80)` | null | — | No |

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
create table orders (
    orderid         int            not null,
    userid          varchar(80)    not null,
    orderdate        date           not null,
    shipaddr1       varchar(80)    not null,
    shipaddr2       varchar(80)    null,
    shipcity        varchar(80)    not null,
    shipstate       varchar(80)    not null,
    shipzip         varchar(20)    not null,
    shipcountry     varchar(20)    not null,
    billaddr1       varchar(80)    not null,
    billaddr2       varchar(80)    null,
    billcity        varchar(80)    not null,
    billstate       varchar(80)    not null,
    billzip         varchar(20)    not null,
    billcountry     varchar(20)    not null,
    courier         varchar(80)    not null,
    totalprice       decimal(10,2)  not null,
    billtofirstname varchar(80)    not null,
    billtolastname  varchar(80)    not null,
    shiptofirstname varchar(80)    not null,
    shiptolastname  varchar(80)    not null,
    creditcard      varchar(80)    not null,
    exprdate        varchar(7)     not null,
    cardtype        varchar(80)    not null,
    locale          varchar(80)    not null,
    constraint pk_orders primary key (orderid)
);
```

**Column Mapping (26 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `orderid` | `INT` | `order_id` | `integer` | not null | PK (`pk_orders`), generated by sequence `order_id_seq` | Yes |
| 2 | `userid` | `VARCHAR(80)` | `userid` | `varchar(80)` | not null | ⚠️ Cross-service ref → `account.userid` — **NO FK** | No (retained) |
| 3 | `orderdate` | `DATE` | `order_date` | `timestamp with time zone` | not null | — | Yes |
| 4 | `shipaddr1` | `VARCHAR(80)` | `ship_addr1` | `varchar(80)` | not null | — | Yes |
| 5 | `shipaddr2` | `VARCHAR(80)` | `ship_addr2` | `varchar(80)` | null | — | Yes |
| 6 | `shipcity` | `VARCHAR(80)` | `ship_city` | `varchar(80)` | not null | — | Yes |
| 7 | `shipstate` | `VARCHAR(80)` | `ship_state` | `varchar(80)` | not null | — | Yes |
| 8 | `shipzip` | `VARCHAR(20)` | `ship_zip` | `varchar(20)` | not null | — | Yes |
| 9 | `shipcountry` | `VARCHAR(20)` | `ship_country` | `varchar(20)` | not null | — | Yes |
| 10 | `billaddr1` | `VARCHAR(80)` | `bill_addr1` | `varchar(80)` | not null | — | Yes |
| 11 | `billaddr2` | `VARCHAR(80)` | `bill_addr2` | `varchar(80)` | null | — | Yes |
| 12 | `billcity` | `VARCHAR(80)` | `bill_city` | `varchar(80)` | not null | — | Yes |
| 13 | `billstate` | `VARCHAR(80)` | `bill_state` | `varchar(80)` | not null | — | Yes |
| 14 | `billzip` | `VARCHAR(20)` | `bill_zip` | `varchar(20)` | not null | — | Yes |
| 15 | `billcountry` | `VARCHAR(20)` | `bill_country` | `varchar(20)` | not null | — | Yes |
| 16 | `courier` | `VARCHAR(80)` | `courier` | `varchar(80)` | not null | — | No |
| 17 | `totalprice` | `DECIMAL(10,2)` | `total_price` | `numeric(10,2)` | not null | — | Yes |
| 18 | `billtofirstname` | `VARCHAR(80)` | `bill_to_first_name` | `varchar(80)` | not null | — | Yes |
| 19 | `billtolastname` | `VARCHAR(80)` | `bill_to_last_name` | `varchar(80)` | not null | — | Yes |
| 20 | `shiptofirstname` | `VARCHAR(80)` | `ship_to_first_name` | `varchar(80)` | not null | — | Yes |
| 21 | `shiptolastname` | `VARCHAR(80)` | `ship_to_last_name` | `varchar(80)` | not null | — | Yes |
| 22 | `creditcard` | `VARCHAR(80)` | `credit_card` | `varchar(80)` | not null | — | Yes |
| 23 | `exprdate` | `VARCHAR(7)` | `expr_date` | `varchar(7)` | not null | — | Yes |
| 24 | `cardtype` | `VARCHAR(80)` | `card_type` | `varchar(80)` | not null | — | Yes |
| 25 | `locale` | `VARCHAR(80)` | `locale` | `varchar(80)` | not null | — | No |
| 26 | _(NEW)_ | — | `status` | `varchar(20)` | null | Default: `'PENDING'` | N/A (new column for Saga orchestration) |

**Renaming Details:**
- `orderid` → `order_id`
- `userid` — retained as `userid` (not renamed; Liquibase changelog comment: "Retains original HSQLDB column name for migration data consistency")
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
- `courier`, `locale`, `userid` — already lowercase, preserved as-is

**New Column (not in HSQLDB):**
- Column 26: `status` `varchar(20)` — added for Saga orchestration state tracking. Values: `PENDING`, `CONFIRMED`, `FAILED`. Default: `'PENDING'`. Not present in the HSQLDB schema; populated with `'CONFIRMED'` for all migrated legacy orders.

**Data Type Changes:**
- `INT` → `integer` (column 1)
- `DATE` → `timestamp with time zone` (column 3) — see [Timezone Assumptions](#timezone-assumptions)
- `DECIMAL(10,2)` → `numeric(10,2)` (column 17)

**Primary Key Generation:**
- The `order_id` PK is generated by PostgreSQL native sequence `order_id_seq`, replacing the HSQLDB `sequence` table's row `('ordernum', ...)`. See [Section 9: Sequence Replacement](#9-sequence-replacement) for details.

**Cross-Service Reference:**
- `userid` references `account.userid` in the Account database — **NO foreign key constraint** in PostgreSQL. Referential integrity is enforced at the application layer by the Order Service calling `GET /api/accounts/{username}` on the Account Service before order creation.

---

### 6.2 Table: `orderstatus`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 96–102):
```sql
create table orderstatus (
    orderid  int        not null,
    linenum  int        not null,
    timestamp date       not null,
    status   varchar(2) not null,
    constraint pk_orderstatus primary key (orderid, linenum)
);
```

**Column Mapping (4 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `orderid` | `INT` | `order_id` | `integer` | not null | Composite PK (part 1) | Yes |
| 2 | `linenum` | `INT` | `line_num` | `integer` | not null | Composite PK (part 2) | Yes |
| 3 | `timestamp` | `DATE` | `timestamp` | `timestamp with time zone` | not null | — | No |
| 4 | `status` | `VARCHAR(2)` | `status` | `varchar(20)` | not null | — | No (type widened) |

**Renaming Details:**
- `orderid` → `order_id`
- `linenum` → `line_num`
- `timestamp`, `status` — preserved as-is

**Data Type Changes:**
- `INT` → `integer` (columns 1, 2)
- `DATE` → `timestamp with time zone` (column 3) — see [Timezone Assumptions](#timezone-assumptions)
- `VARCHAR(2)` → `varchar(20)` (column 4) — widened to accommodate Saga orchestration state values (e.g., `PENDING`, `COMPLETED`, `COMPENSATING`, `FAILED`) in addition to the original monolith value (`P`)

**Primary Key:**
- Composite PK: (`order_id`, `line_num`)

---

### 6.3 Table: `lineitem`

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 104–111):
```sql
create table lineitem (
    orderid  int            not null,
    linenum  int            not null,
    itemid   varchar(10)    not null,
    quantity int            not null,
    unitprice decimal(10,2)  not null,
    constraint pk_lineitem primary key (orderid, linenum)
);
```

**Column Mapping (5 columns):**

| # | HSQLDB Column | HSQLDB Type | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Renamed |
|---|---------------|-------------|-------------------|-----------------|----------|-------------|---------|
| 1 | `orderid` | `INT` | `order_id` | `integer` | not null | Composite PK (part 1) | Yes |
| 2 | `linenum` | `INT` | `line_num` | `integer` | not null | Composite PK (part 2) | Yes |
| 3 | `itemid` | `VARCHAR(10)` | `item_id` | `varchar(10)` | not null | ⚠️ Cross-service ref → `item.item_id` — **NO FK** | Yes |
| 4 | `quantity` | `INT` | `quantity` | `integer` | not null | — | No |
| 5 | `unitprice` | `DECIMAL(10,2)` | `unit_price` | `numeric(10,2)` | not null | — | Yes |

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

### 6.4 Table: `sequence` — NOT MIGRATED

**Source DDL** (from `jpetstore-hsqldb-schema.sql`, lines 160–165):
```sql
create table sequence (
    name   varchar(30) not null,
    nextid int         not null,
    constraint pk_sequence primary key (name)
);
```

> **⚠️ NOT MIGRATED — Replaced by PostgreSQL Native Sequence**
>
> This table is **NOT created** in the Order Service PostgreSQL database. Its functionality is entirely replaced by the PostgreSQL native sequence `order_id_seq`. The HSQLDB `sequence` table row `('ordernum', <nextid>)` is superseded by `CREATE SEQUENCE order_id_seq`. No `CREATE TABLE sequence` exists in the Order Service Liquibase changelog. See [Section 9: Sequence Replacement](#9-sequence-replacement) for full details.
>
> The column mapping below is provided for **reference only** — it documents the HSQLDB source schema but there is no corresponding PostgreSQL target table.

| # | HSQLDB Column | HSQLDB Type | Migration Status |
|---|---------------|-------------|-----------------|
| 1 | `name` | `VARCHAR(30)` | Not migrated — replaced by sequence name `order_id_seq` |
| 2 | `nextid` | `INT` | Not migrated — replaced by `NEXTVAL('order_id_seq')` |

---

## 6A. New PostgreSQL-Only Tables (No HSQLDB Source)

The following tables exist **only** in the PostgreSQL microservice databases and have no counterpart in the HSQLDB monolith schema. They were created to support microservice-specific features (optimistic locking, Saga orchestration, idempotent inventory reservations).

### 6A.1 Table: `inventory_reservation` (Catalog DB — `jpetstore_catalog`)

**Purpose**: Stores inventory reservation records for idempotent inventory decrement/restore operations during Saga-based order processing.

| # | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Description |
|---|-------------------|-----------------|----------|-------------|-------------|
| 1 | `id` | `bigint` | not null | PK (auto-generated) | Surrogate primary key |
| 2 | `item_id` | `varchar(10)` | not null | — | Item being reserved |
| 3 | `order_id` | `varchar(80)` | not null | — | Idempotency key (order requesting reservation) |
| 4 | `quantity` | `integer` | not null | — | Quantity reserved |
| 5 | `reserved_at` | `timestamp with time zone` | not null | — | When the reservation was created |

> **Note**: This table supports the idempotency guarantee for inventory decrement calls — duplicate requests with the same `order_id` + `item_id` return success without double-decrementing.

### 6A.2 Table: `order_saga_state` (Order DB — `jpetstore_order`)

**Purpose**: Persists Saga orchestration state for the distributed order transaction, enabling recovery from failures and tracking progress.

| # | PostgreSQL Column | PostgreSQL Type | Nullable | Constraints | Description |
|---|-------------------|-----------------|----------|-------------|-------------|
| 1 | `saga_id` | `varchar(36)` | not null | PK | Unique Saga execution ID (UUID) |
| 2 | `order_id` | `integer` | not null | — | Associated order ID |
| 3 | `current_step` | `varchar(50)` | not null | — | Current Saga step (e.g., `CREATE_ORDER`, `RESERVE_INVENTORY`, `CONFIRM_ORDER`) |
| 4 | `status` | `varchar(20)` | not null | — | Saga status: `PENDING`, `INVENTORY_RESERVED`, `COMPLETED`, `COMPENSATING`, `FAILED` |
| 5 | `created_at` | `timestamp with time zone` | not null | — | Saga creation timestamp |
| 6 | `updated_at` | `timestamp with time zone` | not null | — | Last status update timestamp |

> **Note**: The `status` values in this table (`COMPLETED`, `FAILED`, etc.) track the **Saga's internal orchestration state** and are distinct from the business-facing `orders.status` column (`CONFIRMED`, `FAILED`). `COMPLETED` (saga) corresponds to `CONFIRMED` (order).

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
| **Target (PostgreSQL)** | `orders.userid` (Order DB, retained) references `account.userid` (Account DB, HSQLDB-identical) — stored as plain `varchar(80)` field with **NO FK constraint** |
| **Source Database** | `jpetstore_order` (Order Service) |
| **Target Database** | `jpetstore_account` (Account Service) |
| **Application-Layer Enforcement** | Order Service validates user existence via synchronous REST call: `GET /api/accounts/{username}` on Account Service before creating an order |
| **Failure Behavior** | If Account Service returns 404 or is unavailable, the order creation fails with an appropriate error — no orphaned orders are created |

---

## 8. Index Mapping

The HSQLDB schema defines 3 indexes. All are on tables in the Catalog database and are preserved in PostgreSQL with their **original HSQLDB index names** (no renaming applied).

| # | HSQLDB Index Name | Table | HSQLDB Column(s) | PostgreSQL Index Name | PostgreSQL Column(s) | Database |
|---|-------------------|-------|-------------------|----------------------|---------------------|----------|
| 1 | `productCat` | `product` | `category` | `productCat` | `category` | `jpetstore_catalog` |
| 2 | `productName` | `product` | `name` | `productName` | `name` | `jpetstore_catalog` |
| 3 | `itemProd` | `item` | `productid` | `itemProd` | `productid` | `jpetstore_catalog` |

**Notes:**
- Index names are **retained exactly as defined in HSQLDB** (matching the Liquibase `indexName` attributes). No `idx_*` renaming convention is applied.
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

| Database | Bounded Context | HSQLDB Tables Migrated | New PostgreSQL-Only Tables | Total Tables | Total Columns |
|----------|----------------|----------------------|--------------------------|-------------|--------------|
| `jpetstore_account` | Account / User Management | 4 | 0 | 4 | 21 |
| `jpetstore_catalog` | Catalog / Inventory | 5 | 1 (`inventory_reservation`) | 6 | 36 (30 migrated + 1 added `version` + 5 new table) |
| `jpetstore_order` | Order / Cart | 3 (`sequence` NOT migrated) | 1 (`order_saga_state`) | 4 | 41 (35 migrated + 1 added `status` + 6 new table — excludes unmigrated `sequence`) |
| **Total** | | **12** (of 13; `sequence` not migrated) | **2** | **14** | **98** |

### Data Type Conversion Summary

| HSQLDB Type | PostgreSQL Type | Occurrences | Notes |
|-------------|----------------|-------------|-------|
| `VARCHAR(n)` | `varchar(n)` | 66 columns | Length preserved (except `orderstatus.status`: widened from `varchar(2)` to `varchar(20)`) |
| `INT` | `integer` | 14 columns | Standard integer mapping |
| `INT` | `boolean` | 2 columns | `profile.mylistopt`, `profile.banneropt` — 0→false, non-zero→true |
| `DECIMAL(10,2)` | `numeric(10,2)` | 4 columns | Precision and scale preserved |
| `DATE` | `timestamp with time zone` | 2 columns | `orders.order_date`, `orderstatus.timestamp` — timezone awareness for distributed system |
| `VARCHAR(2)` | `varchar(20)` | 1 column | `orderstatus.status` — widened for Saga state values (`PENDING`, `COMPLETED`, `COMPENSATING`, `FAILED`) |

### Column Renaming Summary

| Category | Count | Examples |
|----------|-------|---------|
| Order Service columns renamed to snake_case | 22 | `orderid` → `order_id`, `billtofirstname` → `bill_to_first_name` |
| Order Service columns retained (HSQLDB-identical) | 4 | `userid`, `courier`, `locale`, `quantity` |
| Account/Catalog columns preserved (HSQLDB-identical) | 51 | `userid`, `firstname`, `catid`, `productid`, `email`, `status` |
| Columns added (not in HSQLDB) | 2 | `inventory.version` (optimistic locking), `orders.status` (Saga state) |
| New PostgreSQL-only tables | 2 | `inventory_reservation` (5 cols), `order_saga_state` (6 cols) |

### Constraint Summary

| Constraint Type | Count | Details |
|----------------|-------|---------|
| Primary Keys preserved | 12 | One per migrated table (2 composite PKs: `orderstatus`, `lineitem`; `sequence` not migrated) |
| Intra-service FKs preserved | 3 | `product.category → category.catid`, `item.productid → product.productid`, `item.supplier → supplier.suppid` |
| Cross-service FKs removed | 2 | `orders.userid → account.userid`, `lineitem.item_id → item.itemid` |
| Indexes preserved | 3 | `productCat`, `productName`, `itemProd` (original HSQLDB names retained) |
| Sequences added | 1 | `order_id_seq` (replaces `sequence` table) |
