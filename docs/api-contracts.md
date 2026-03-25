# JPetStore REST API Contracts

> **Version**: 1.0  
> **Last Updated**: 2025-01-01  
> **Status**: Canonical Contract — All implementations must conform to this specification

## Table of Contents

- [1. Introduction](#1-introduction)
- [2. Authentication](#2-authentication)
- [3. Account Service API](#3-account-service-api)
  - [3.1 POST /api/accounts/signon](#31-post-apiaccountssignon)
  - [3.2 GET /api/accounts/{username}](#32-get-apiaccountsusername)
  - [3.3 POST /api/accounts](#33-post-apiaccounts)
  - [3.4 PUT /api/accounts/{username}](#34-put-apiaccountsusername)
- [4. Catalog Service API](#4-catalog-service-api)
  - [4.1 GET /api/categories](#41-get-apicategories)
  - [4.2 GET /api/categories/{categoryId}](#42-get-apicategoriescategoryid)
  - [4.3 GET /api/products?categoryId={categoryId}](#43-get-apiproductscategoryidcategoryid)
  - [4.4 GET /api/products/{productId}](#44-get-apiproductsproductid)
  - [4.5 GET /api/products/search?keywords={keywords}](#45-get-apiproductssearchkeywordskeywords)
  - [4.6 GET /api/items?productId={productId}](#46-get-apiitemsproductidproductid)
  - [4.7 GET /api/items/{itemId}](#47-get-apiitemsitemid)
  - [4.8 GET /api/items/{itemId}/inventory](#48-get-apiitemsitemidinventory)
  - [4.9 POST /api/items/{itemId}/inventory/decrement](#49-post-apiitemsitemidinventorydecrement)
  - [4.10 POST /api/items/{itemId}/inventory/restore](#410-post-apiitemsitemidinventoryrestore)
- [5. Order Service API](#5-order-service-api)
  - [5.1 POST /api/orders](#51-post-apiorders)
  - [5.2 GET /api/orders?username={username}](#52-get-apiordersusernameusername)
  - [5.3 GET /api/orders/{orderId}](#53-get-apiordersorderid)
  - [5.4 GET /api/cart/{sessionId}](#54-get-apicartsessionid)
  - [5.5 POST /api/cart/{sessionId}/items](#55-post-apicartsessioniditems)
  - [5.6 DELETE /api/cart/{sessionId}/items/{itemId}](#56-delete-apicartsessioniditemsitemid)
  - [5.7 PUT /api/cart/{sessionId}](#57-put-apicartsessionid)
- [6. Error Handling Convention](#6-error-handling-convention)
- [7. Common Headers](#7-common-headers)
- [8. Cross-Service Communication Patterns](#8-cross-service-communication-patterns)

---

## 1. Introduction

This document defines the canonical REST API contracts for the three microservices extracted from the MyBatis JPetStore 6 monolith as part of the Strangler Fig decomposition:

- **Account Service** — Manages user accounts, authentication, and profile data. Base path: `/api/accounts`
- **Catalog Service** — Provides read-only catalog browsing (categories, products, items) and inventory management. Base path: `/api`
- **Order Service** — Handles order placement, order history, and externalized cart state. Base path: `/api`

These REST APIs replace the monolith's in-process `@SpringBean` injection between Stripes ActionBeans and Spring-managed service classes. The monolith's updated ActionBeans call these REST APIs via HTTP clients (`RestTemplate`), and the three microservices expose these endpoints for the API Gateway to route to.

**Key conventions:**

- All request and response bodies use `application/json` format
- All date/time values use ISO 8601 format (e.g., `"2025-01-15T10:30:00Z"`)
- All monetary values are represented as JSON numbers with decimal precision (e.g., `18.50`)
- Field names in JSON responses match the camelCase naming conventions of the original domain POJOs (`Account.java`, `Order.java`, `Item.java`, etc.)
- Null fields may be omitted from responses or included with a `null` value

---

## 2. Authentication

### 2.1 JWT-Based Authentication

Authentication is handled via JSON Web Tokens (JWT) issued by the Account Service:

- **Token issuance**: The Account Service `POST /api/accounts/signon` endpoint validates credentials and returns a signed JWT
- **Token claims**: The JWT payload contains `username` (string) and `accountId` (string) claims, along with standard `iat` (issued at) and `exp` (expiration) claims
- **Token transport**: Clients pass the token via the `Authorization: Bearer {token}` HTTP header, or alternatively via an HTTP-only cookie named `JPETSTORE_TOKEN`
- **Token validation**: The API Gateway validates the JWT signature and expiration before forwarding requests to protected endpoints. Valid claims are passed downstream via `X-Auth-Username` and `X-Auth-AccountId` headers

### 2.2 Endpoint Authentication Requirements

| Access Level | Description | Endpoints |
|-------------|-------------|-----------|
| **Public** | No authentication required | All Catalog Service endpoints, `POST /api/accounts/signon`, `POST /api/accounts` |
| **Protected** | Valid JWT required | `GET /api/accounts/{username}`, `PUT /api/accounts/{username}`, `POST /api/orders`, `GET /api/orders`, `GET /api/orders/{orderId}` |
| **Optional** | Anonymous access supported; authenticated users get enhanced behavior | `GET /api/cart/{sessionId}`, `POST /api/cart/{sessionId}/items`, `DELETE /api/cart/{sessionId}/items/{itemId}`, `PUT /api/cart/{sessionId}` |
| **Service-to-Service** | Internal only; not exposed via the API Gateway public routes | `POST /api/items/{itemId}/inventory/decrement`, `POST /api/items/{itemId}/inventory/restore` |

### 2.3 Anonymous Cart Support

Unauthenticated users can browse the catalog and build a cart before signing in. Anonymous carts are identified by a session cookie value. On login, the anonymous cart is merged into the user's persistent cart, preserving the monolith's existing behavior where `CartActionBean` (session-scoped) persists across the authentication transition.

---

## 3. Account Service API

**Base URL**: `http://account-service:8081/api/accounts`

The Account Service owns the `account`, `profile`, `signon`, and `bannerdata` tables. It replaces the monolith's `AccountService.java` and the authentication portions of `AccountActionBean.java`.

### 3.1 POST /api/accounts/signon

**Purpose**: Authenticate a user and issue a JWT token.

Replaces the monolith's `AccountActionBean.signon()` which called `accountService.getAccount(username, password)`.

**Authentication**: None (public endpoint)

**Request Body** (`SignonRequest`):

```json
{
  "username": "string (required)",
  "password": "string (required)"
}
```

**Success Response** — `200 OK` (`SignonResponse`):

```json
{
  "token": "string (JWT)",
  "username": "string",
  "email": "string"
}
```

> **Note**: The `SignonResponse` intentionally contains only three fields: the JWT token, the authenticated username, and the email address. The password is never included — matching the monolith behavior where `account.setPassword(null)` is called after successful signon (see `AccountActionBean.signon()` line 169). Full account details (address, phone, preferences, etc.) can be retrieved separately via `GET /api/accounts/{username}`.

**Error Response** — `401 Unauthorized`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid username or password.  Signon failed.",
  "path": "/api/accounts/signon"
}
```

> **Important**: The error message preserves the exact wording from `AccountActionBean.signon()` line 164, including the double space between "password." and "Signon".

---

### 3.2 GET /api/accounts/{username}

**Purpose**: Retrieve account details by username.

Replaces the monolith's `accountService.getAccount(username)` (single-argument overload).

**Authentication**: Required (JWT Bearer token)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `username` | string | Yes | The account username (e.g., `"j2ee"`, `"ACID"`) |

**Success Response** — `200 OK` (`AccountDTO`):

```json
{
  "username": "j2ee",
  "email": "yourname@yourdomain.com",
  "firstName": "ABC",
  "lastName": "XYX",
  "status": "OK",
  "address1": "901 San Antonio Road",
  "address2": "MS UCUP02-206",
  "city": "Palo Alto",
  "state": "CA",
  "zip": "94303",
  "country": "USA",
  "phone": "555-555-5555",
  "languagePreference": "english",
  "favouriteCategoryId": "DOGS",
  "listOption": true,
  "bannerOption": true,
  "bannerName": "<image src=\"../images/banner_dogs.gif\">"
}
```

> **Field mapping**: Response field names match the `Account.java` domain POJO property names exactly. The `bannerName` value comes from the `bannerdata` table joined via `favouriteCategoryId`.

**Error Response** — `404 Not Found`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Account not found",
  "path": "/api/accounts/unknownuser"
}
```

---

### 3.3 POST /api/accounts

**Purpose**: Create a new user account with auto-login.

Replaces the monolith's `AccountActionBean.newAccount()` which called `accountService.insertAccount(account)` followed by setting `authenticated = true`.

**Authentication**: None (public endpoint — registration)

**Request Body** (`AccountDTO`):

```json
{
  "username": "string (required)",
  "password": "string (required)",
  "email": "string (required)",
  "firstName": "string (required)",
  "lastName": "string (required)",
  "status": "string (optional, defaults to null)",
  "address1": "string (required)",
  "address2": "string | null (optional)",
  "city": "string (required)",
  "state": "string (required)",
  "zip": "string (required)",
  "country": "string (required)",
  "phone": "string (required)",
  "languagePreference": "string (required)",
  "favouriteCategoryId": "string | null (optional)",
  "listOption": "boolean (required)",
  "bannerOption": "boolean (required)"
}
```

**Success Response** — `201 Created` (`SignonResponse`):

```json
{
  "token": "string (JWT)",
  "username": "string",
  "email": "string"
}
```

> **Auto-login**: The response includes a JWT token so the user is automatically authenticated after registration, matching the monolith behavior where `authenticated = true` is set after `insertAccount()` (see `AccountActionBean.newAccount()` line 119). Full account details can be retrieved separately via `GET /api/accounts/{username}`.

**Error Responses**:

- `409 Conflict` — Username already exists:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 409,
    "error": "Conflict",
    "message": "Username already exists",
    "path": "/api/accounts"
  }
  ```

- `400 Bad Request` — Validation errors:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 400,
    "error": "Bad Request",
    "message": "Validation failed",
    "path": "/api/accounts",
    "fieldErrors": [
      { "field": "firstName", "message": "must not be blank" },
      { "field": "lastName", "message": "must not be blank" }
    ]
  }
  ```

**Transactional Behavior**: The insert is a 3-table atomic transaction matching `AccountService.insertAccount()`:
1. `accountMapper.insertAccount(account)` — inserts into the `account` table
2. `accountMapper.insertProfile(account)` — inserts into the `profile` table
3. `accountMapper.insertSignon(account)` — inserts into the `signon` table

If any step fails, the entire transaction is rolled back.

---

### 3.4 PUT /api/accounts/{username}

**Purpose**: Update an existing account's details.

Replaces the monolith's `AccountActionBean.editAccount()` which called `accountService.updateAccount(account)`.

**Authentication**: Required (JWT Bearer token — the authenticated user's `username` claim must match the `{username}` path parameter)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `username` | string | Yes | The account username to update |

**Request Body** (partial `AccountDTO` — all fields optional for partial update):

```json
{
  "password": "string | null (optional — only updates signon if non-empty)",
  "email": "string (optional)",
  "firstName": "string (optional)",
  "lastName": "string (optional)",
  "status": "string (optional)",
  "address1": "string (optional)",
  "address2": "string | null (optional)",
  "city": "string (optional)",
  "state": "string (optional)",
  "zip": "string (optional)",
  "country": "string (optional)",
  "phone": "string (optional)",
  "languagePreference": "string (optional)",
  "favouriteCategoryId": "string | null (optional)",
  "listOption": "boolean (optional)",
  "bannerOption": "boolean (optional)"
}
```

> **Conditional password update**: The `signon` table is only updated if the `password` field is present and non-empty. This matches the monolith's `AccountService.updateAccount()` behavior: `Optional.ofNullable(account.getPassword()).filter(password -> password.length() > 0).ifPresent(...)`.

**Success Response** — `200 OK` (`AccountDTO`):

Returns the full updated account details (same structure as `GET /api/accounts/{username}`).

**Error Responses**:

- `404 Not Found` — Account does not exist:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 404,
    "error": "Not Found",
    "message": "Account not found",
    "path": "/api/accounts/unknownuser"
  }
  ```

- `403 Forbidden` — Authenticated user trying to update another user's account:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 403,
    "error": "Forbidden",
    "message": "You can only update your own account",
    "path": "/api/accounts/otheruser"
  }
  ```

**Transactional Behavior**: The update is an atomic transaction matching `AccountService.updateAccount()`:
1. `accountMapper.updateAccount(account)` — updates the `account` table
2. `accountMapper.updateProfile(account)` — updates the `profile` table
3. Conditionally: `accountMapper.updateSignon(account)` — updates the `signon` table only if password is non-empty

---

## 4. Catalog Service API

**Base URL**: `http://catalog-service:8082/api`

The Catalog Service owns the `category`, `product`, `item`, `inventory`, and `supplier` tables. It replaces the monolith's `CatalogService.java` and the inventory-related operations from `ItemMapper`. All public endpoints are read-only. The inventory decrement/restore endpoints are internal (service-to-service only).

### 4.1 GET /api/categories

**Purpose**: List all product categories.

Replaces the monolith's `catalogService.getCategoryList()` called by `CatalogActionBean.viewCategory()`.

**Authentication**: None (public)

**Success Response** — `200 OK` (`List<CategoryDTO>`):

```json
[
  {
    "categoryId": "FISH",
    "name": "Fish",
    "description": "<image src=\"../images/fish_icon.gif\"><font size=\"5\" color=\"blue\"> Fish</font>"
  },
  {
    "categoryId": "DOGS",
    "name": "Dogs",
    "description": "<image src=\"../images/dogs_icon.gif\"><font size=\"5\" color=\"blue\"> Dogs</font>"
  },
  {
    "categoryId": "REPTILES",
    "name": "Reptiles",
    "description": "<image src=\"../images/reptiles_icon.gif\"><font size=\"5\" color=\"blue\"> Reptiles</font>"
  },
  {
    "categoryId": "CATS",
    "name": "Cats",
    "description": "<image src=\"../images/cats_icon.gif\"><font size=\"5\" color=\"blue\"> Cats</font>"
  },
  {
    "categoryId": "BIRDS",
    "name": "Birds",
    "description": "<image src=\"../images/birds_icon.gif\"><font size=\"5\" color=\"blue\"> Birds</font>"
  }
]
```

> **Field mapping**: `categoryId` maps to `catid` in the `category` table. `description` maps to `descn`. The description values contain legacy HTML markup that is preserved exactly as stored.

---

### 4.2 GET /api/categories/{categoryId}

**Purpose**: Get a single category by its ID.

Replaces the monolith's `catalogService.getCategory(categoryId)`.

**Authentication**: None (public)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `categoryId` | string | Yes | Category identifier (e.g., `"FISH"`, `"DOGS"`, `"REPTILES"`, `"CATS"`, `"BIRDS"`) |

**Success Response** — `200 OK` (`CategoryDTO`):

```json
{
  "categoryId": "FISH",
  "name": "Fish",
  "description": "<image src=\"../images/fish_icon.gif\"><font size=\"5\" color=\"blue\"> Fish</font>"
}
```

**Error Response** — `404 Not Found`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Category not found",
  "path": "/api/categories/UNKNOWN"
}
```

---

### 4.3 GET /api/products?categoryId={categoryId}

**Purpose**: List all products within a category.

Replaces the monolith's `catalogService.getProductListByCategory(categoryId)` called by `CatalogActionBean.viewCategory()` and `AccountActionBean` for personalization (`myList`).

**Authentication**: None (public)

**Query Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `categoryId` | string | Yes | Category identifier to filter by (e.g., `"FISH"`) |

**Success Response** — `200 OK` (`List<ProductDTO>`):

```json
[
  {
    "productId": "FI-SW-01",
    "categoryId": "FISH",
    "name": "Angelfish",
    "description": "<image src=\"../images/fish1.gif\">Salt Water fish from Australia"
  },
  {
    "productId": "FI-SW-02",
    "categoryId": "FISH",
    "name": "Tiger Shark",
    "description": "<image src=\"../images/fish4.gif\">Salt Water fish from Australia"
  }
]
```

> **Cross-service usage**: This endpoint is also called by the Account Service for personalization. After successful signon or account edit, the Account Service calls `GET /api/products?categoryId={account.favouriteCategoryId}` to populate the `myList` product list (see `AccountActionBean` lines 118, 140, 170). If the Catalog Service is unavailable, the Account Service returns an empty list as a graceful fallback.

> **Field mapping**: `productId` maps to `productid` in the `product` table. `categoryId` maps to `category`. `description` maps to `descn`.

---

### 4.4 GET /api/products/{productId}

**Purpose**: Get a single product by its ID.

Replaces the monolith's `catalogService.getProduct(productId)`.

**Authentication**: None (public)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `productId` | string | Yes | Product identifier (e.g., `"FI-SW-01"`, `"K9-BD-01"`) |

**Success Response** — `200 OK` (`ProductDTO`):

```json
{
  "productId": "FI-SW-01",
  "categoryId": "FISH",
  "name": "Angelfish",
  "description": "<image src=\"../images/fish1.gif\">Salt Water fish from Australia"
}
```

**Error Response** — `404 Not Found`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Product not found",
  "path": "/api/products/UNKNOWN"
}
```

---

### 4.5 GET /api/products/search?keywords={keywords}

**Purpose**: Search products by keywords.

Replaces the monolith's `catalogService.searchProductList(keywords)` called by `CatalogActionBean.searchProducts()`.

**Authentication**: None (public)

**Query Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `keywords` | string | Yes | Space-separated search keywords (e.g., `"Angelfish"`, `"fish shark"`) |

**Success Response** — `200 OK` (`List<ProductDTO>`):

```json
[
  {
    "productId": "FI-SW-01",
    "categoryId": "FISH",
    "name": "Angelfish",
    "description": "<image src=\"../images/fish1.gif\">Salt Water fish from Australia"
  }
]
```

Returns an empty array `[]` if no products match.

**Implementation Notes**:

The search implementation must replicate the monolith's `CatalogService.searchProductList()` behavior exactly:

1. The `keywords` string is tokenized by splitting on whitespace (`\\s+`)
2. Each keyword is lowercased
3. Each keyword is wrapped with SQL wildcards: `"%" + keyword.toLowerCase() + "%"`
4. A query is executed for each keyword against the product `name` column
5. Results from all keyword queries are aggregated into a single list

This means searching for `"fish shark"` returns all products whose name contains `"fish"` OR `"shark"` (union, not intersection).

---

### 4.6 GET /api/items?productId={productId}

**Purpose**: List all items for a given product.

Replaces the monolith's `catalogService.getItemListByProduct(productId)` called by `CatalogActionBean.viewProduct()`.

**Authentication**: None (public)

**Query Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `productId` | string | Yes | Product identifier to filter by (e.g., `"FI-SW-01"`) |

**Success Response** — `200 OK` (`List<ItemDTO>`):

```json
[
  {
    "itemId": "EST-1",
    "productId": "FI-SW-01",
    "listPrice": 16.50,
    "unitCost": 10.00,
    "supplierId": 1,
    "status": "P",
    "attribute1": "Large",
    "attribute2": null,
    "attribute3": null,
    "attribute4": null,
    "attribute5": null,
    "product": {
      "productId": "FI-SW-01",
      "categoryId": "FISH",
      "name": "Angelfish",
      "description": "<image src=\"../images/fish1.gif\">Salt Water fish from Australia"
    },
    "quantity": 10000
  }
]
```

> **Field mapping**: `itemId` maps to `itemid` in the `item` table. `listPrice` maps to `listprice` (DECIMAL(10,2)). `unitCost` maps to `unitcost`. `supplierId` maps to `supplier`. `attribute1`–`attribute5` map to `attr1`–`attr5`. `quantity` comes from the `inventory` table (joined by `itemid`). The embedded `product` object comes from the `product` table joined via `productid`.

---

### 4.7 GET /api/items/{itemId}

**Purpose**: Get a single item by its ID, including product details and inventory quantity.

Replaces the monolith's `catalogService.getItem(itemId)`.

**Authentication**: None (public)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `itemId` | string | Yes | Item identifier (e.g., `"EST-1"`, `"EST-14"`) |

**Success Response** — `200 OK` (`ItemDTO`):

```json
{
  "itemId": "EST-1",
  "productId": "FI-SW-01",
  "listPrice": 16.50,
  "unitCost": 10.00,
  "supplierId": 1,
  "status": "P",
  "attribute1": "Large",
  "attribute2": null,
  "attribute3": null,
  "attribute4": null,
  "attribute5": null,
  "product": {
    "productId": "FI-SW-01",
    "categoryId": "FISH",
    "name": "Angelfish",
    "description": "<image src=\"../images/fish1.gif\">Salt Water fish from Australia"
  },
  "quantity": 10000
}
```

**Error Response** — `404 Not Found`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Item not found",
  "path": "/api/items/UNKNOWN"
}
```

---

### 4.8 GET /api/items/{itemId}/inventory

**Purpose**: Check whether an item is in stock and retrieve its current inventory quantity.

Replaces the monolith's `catalogService.isItemInStock(itemId)` which calls `itemMapper.getInventoryQuantity(itemId)` and returns `quantity > 0`.

**Authentication**: None (public)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `itemId` | string | Yes | Item identifier (e.g., `"EST-1"`) |

**Success Response** — `200 OK`:

```json
{
  "itemId": "EST-1",
  "quantity": 10000,
  "inStock": true
}
```

> **Business rule**: `inStock` is `true` when `quantity > 0`, matching `CatalogService.isItemInStock()`: `return itemMapper.getInventoryQuantity(itemId) > 0`.

**Error Response** — `404 Not Found`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Item not found",
  "path": "/api/items/UNKNOWN/inventory"
}
```

---

### 4.9 POST /api/items/{itemId}/inventory/decrement

**Purpose**: Atomically decrement inventory for a given item during order placement. This endpoint is called by the Order Service as part of the Saga orchestration pattern.

Replaces the monolith's `itemMapper.updateInventoryQuantity(param)` which executed `UPDATE inventory SET qty = qty - #{increment} WHERE itemid = #{itemId}`.

**Authentication**: Service-to-service (internal — not exposed via API Gateway public routes)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `itemId` | string | Yes | Item identifier (e.g., `"EST-1"`) |

**Request Body** (`InventoryDecrementRequest`):

```json
{
  "quantity": 2,
  "orderId": 1001
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `quantity` | integer | Yes | Amount to decrement (must be > 0) |
| `orderId` | integer | Yes | Idempotency key — the order ID requesting this decrement |

**Success Response** — `200 OK`:

```json
{
  "itemId": "EST-1",
  "remainingQuantity": 9998,
  "reservationId": "res-EST-1-1001"
}
```

**Error Responses**:

- `409 Conflict` — Insufficient inventory:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 409,
    "error": "Conflict",
    "message": "Insufficient inventory",
    "path": "/api/items/EST-1/inventory/decrement"
  }
  ```
  Returned when the current inventory quantity is less than the requested decrement amount.

- `404 Not Found` — Item does not exist:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 404,
    "error": "Not Found",
    "message": "Item not found",
    "path": "/api/items/UNKNOWN/inventory/decrement"
  }
  ```

**Idempotency**: Requests with the same `orderId` for the same `itemId` return success without double-decrementing. The Catalog Service stores reservation records indexed by `orderId` + `itemId` and returns the original success response for duplicate requests. This prevents phantom decrements during Saga retries.

**Atomicity**: The inventory decrement uses optimistic locking (`@Version` on the `Inventory` entity) to guarantee atomic updates under concurrent access, replacing the monolith's non-thread-safe approach.

---

### 4.10 POST /api/items/{itemId}/inventory/restore

**Purpose**: Compensating action — restore inventory after a failed order. This is the compensating transaction for the inventory decrement step of the Order Saga.

**Authentication**: Service-to-service (internal — not exposed via API Gateway public routes)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `itemId` | string | Yes | Item identifier (e.g., `"EST-1"`) |

**Request Body**:

```json
{
  "quantity": 2,
  "orderId": 1001
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `quantity` | integer | Yes | Amount to restore (must match the original decrement) |
| `orderId` | integer | Yes | The order ID for which inventory was originally decremented (for tracing and idempotency) |

**Success Response** — `200 OK`:

```json
{
  "itemId": "EST-1",
  "restoredQuantity": 10000
}
```

**Error Response** — `404 Not Found`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Item not found",
  "path": "/api/items/UNKNOWN/inventory/restore"
}
```

> **Idempotency**: Restore requests for an `orderId` that has already been restored (or was never decremented) return success without over-restoring.

---

## 5. Order Service API

**Base URL**: `http://order-service:8083/api`

The Order Service owns the `orders`, `orderstatus`, and `lineitem` tables, plus the externalized cart state (Redis-backed). It replaces the monolith's `OrderService.java` and the cart/order portions of `CartActionBean.java` and `OrderActionBean.java`.

### 5.1 POST /api/orders

**Purpose**: Place a new order using the Saga orchestration pattern.

Replaces the monolith's `OrderActionBean.newOrder()` which called `orderService.insertOrder(order)`. The monolith's `Order.initOrder(Account, Cart)` method populated the order from account and cart data — in the decomposed system, the client sends this data directly.

**Authentication**: Required (JWT Bearer token)

**Request Body** (`OrderRequest`):

```json
{
  "username": "j2ee",
  "shipToFirstName": "ABC",
  "shipToLastName": "XYX",
  "shipAddress1": "901 San Antonio Road",
  "shipAddress2": "MS UCUP02-206",
  "shipCity": "Palo Alto",
  "shipState": "CA",
  "shipZip": "94303",
  "shipCountry": "USA",
  "billToFirstName": "ABC",
  "billToLastName": "XYX",
  "billAddress1": "901 San Antonio Road",
  "billAddress2": "MS UCUP02-206",
  "billCity": "Palo Alto",
  "billState": "CA",
  "billZip": "94303",
  "billCountry": "USA",
  "creditCard": "999 9999 9999 9999",
  "expiryDate": "12/03",
  "cardType": "Visa",
  "courier": "UPS",
  "locale": "CA",
  "lineItems": [
    {
      "itemId": "EST-1",
      "quantity": 2,
      "unitPrice": 16.50
    },
    {
      "itemId": "EST-14",
      "quantity": 1,
      "unitPrice": 58.50
    }
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `username` | string | Yes | Must match the authenticated user's JWT `username` claim |
| `shipToFirstName` | string | Yes | Shipping first name |
| `shipToLastName` | string | Yes | Shipping last name |
| `shipAddress1` | string | Yes | Shipping address line 1 |
| `shipAddress2` | string | No | Shipping address line 2 |
| `shipCity` | string | Yes | Shipping city |
| `shipState` | string | Yes | Shipping state |
| `shipZip` | string | Yes | Shipping ZIP code |
| `shipCountry` | string | Yes | Shipping country |
| `billToFirstName` | string | Yes | Billing first name |
| `billToLastName` | string | Yes | Billing last name |
| `billAddress1` | string | Yes | Billing address line 1 |
| `billAddress2` | string | No | Billing address line 2 |
| `billCity` | string | Yes | Billing city |
| `billState` | string | Yes | Billing state |
| `billZip` | string | Yes | Billing ZIP code |
| `billCountry` | string | Yes | Billing country |
| `creditCard` | string | Yes | Credit card number |
| `expiryDate` | string | Yes | Card expiry date (e.g., `"12/03"`) |
| `cardType` | string | Yes | One of: `"Visa"`, `"MasterCard"`, `"American Express"` |
| `courier` | string | Yes | Shipping courier (e.g., `"UPS"`) |
| `locale` | string | Yes | Locale identifier (e.g., `"CA"`) |
| `lineItems` | array | Yes | Order line items (at least one required) |
| `lineItems[].itemId` | string | Yes | Catalog item ID |
| `lineItems[].quantity` | integer | Yes | Quantity ordered (must be > 0) |
| `lineItems[].unitPrice` | number | Yes | Unit price at time of order (decimal) |

> **Credit card types**: The `cardType` field must be one of exactly `["Visa", "MasterCard", "American Express"]`, matching the monolith's `OrderActionBean.CARD_TYPE_LIST` static initializer.

> **Field naming**: All field names match the `Order.java` domain POJO property names exactly (e.g., `shipToFirstName`, `billAddress1`, `expiryDate`).

**Success Response** — `201 Created` (`OrderDTO`):

```json
{
  "orderId": 1000,
  "username": "j2ee",
  "orderDate": "2025-01-15T10:30:00Z",
  "status": "CONFIRMED",
  "shipToFirstName": "ABC",
  "shipToLastName": "XYX",
  "shipAddress1": "901 San Antonio Road",
  "shipAddress2": "MS UCUP02-206",
  "shipCity": "Palo Alto",
  "shipState": "CA",
  "shipZip": "94303",
  "shipCountry": "USA",
  "billToFirstName": "ABC",
  "billToLastName": "XYX",
  "billAddress1": "901 San Antonio Road",
  "billAddress2": "MS UCUP02-206",
  "billCity": "Palo Alto",
  "billState": "CA",
  "billZip": "94303",
  "billCountry": "USA",
  "creditCard": "999 9999 9999 9999",
  "expiryDate": "12/03",
  "cardType": "Visa",
  "courier": "UPS",
  "locale": "CA",
  "totalPrice": 91.50,
  "lineItems": [
    {
      "lineNumber": 1,
      "itemId": "EST-1",
      "quantity": 2,
      "unitPrice": 16.50,
      "totalPrice": 33.00
    },
    {
      "lineNumber": 2,
      "itemId": "EST-14",
      "quantity": 1,
      "unitPrice": 58.50,
      "totalPrice": 58.50
    }
  ]
}
```

> **Order ID type and generation**: The `orderId` field is an **integer** (Java `int`) across all endpoints and DTOs. It is generated by a PostgreSQL sequence (`order_id_seq`), replacing the monolith's non-thread-safe `sequence` table. Starting value is set to a value greater than the maximum migrated order ID.

> **Total price**: Calculated as the sum of `quantity * unitPrice` for each line item, matching the monolith's `Cart.getSubTotal()` calculation.

**Error Responses**:

- `409 Conflict` — Insufficient inventory (Saga failure):
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 409,
    "error": "Conflict",
    "message": "Insufficient inventory for item EST-1",
    "path": "/api/orders"
  }
  ```
  Returned when the Catalog Service reports insufficient inventory for one or more line items. The order is created with status `FAILED` and no inventory is decremented (or already-decremented inventory is compensated via the restore endpoint).

- `503 Service Unavailable` — Catalog Service unreachable during Saga:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 503,
    "error": "Service Unavailable",
    "message": "Unable to process order: Catalog Service is unavailable",
    "path": "/api/orders"
  }
  ```

- `400 Bad Request` — Invalid card type or missing required fields:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 400,
    "error": "Bad Request",
    "message": "Invalid card type. Must be one of: Visa, MasterCard, American Express",
    "path": "/api/orders"
  }
  ```

**Saga Flow**:

The order placement follows the orchestration-based Saga pattern:

1. **Create Order (PENDING)**: The Order Service generates an order ID, inserts the order record with status `PENDING`, inserts the `orderstatus` record, and inserts all `lineitem` records into its own database
2. **Reserve Inventory**: For each line item, the Order Service calls `POST /api/items/{itemId}/inventory/decrement` on the Catalog Service, passing the `orderId` as an idempotency key
3. **Confirm or Compensate**:
   - If all inventory reservations succeed: update order status to `CONFIRMED`
   - If any reservation fails: trigger compensating transactions (restore already-decremented inventory via `POST /api/items/{itemId}/inventory/restore`), then update order status to `FAILED`

After a successful order, the monolith clears the cart (matching `CartActionBean.clear()` called after `orderService.insertOrder(order)` in `OrderActionBean.newOrder()`).

---

### 5.2 GET /api/orders?username={username}

**Purpose**: List all orders for a given username.

Replaces the monolith's `orderService.getOrdersByUsername(username)` called by `OrderActionBean.listOrders()`.

**Authentication**: Required (JWT Bearer token — the authenticated user's `username` claim must match the `username` query parameter)

**Query Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `username` | string | Yes | Username to list orders for |

**Success Response** — `200 OK` (`List<OrderDTO>` — summary):

```json
[
  {
    "orderId": 1000,
    "username": "j2ee",
    "orderDate": "2025-01-15T10:30:00Z",
    "totalPrice": 91.50,
    "status": "CONFIRMED"
  },
  {
    "orderId": 1001,
    "username": "j2ee",
    "orderDate": "2025-01-16T14:00:00Z",
    "totalPrice": 16.50,
    "status": "CONFIRMED"
  }
]
```

Returns an empty array `[]` if the user has no orders.

**Error Response** — `403 Forbidden`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "You may only view your own orders.",
  "path": "/api/orders"
}
```

> **Authorization**: The error message matches the monolith's `OrderActionBean.viewOrder()` line 182.

---

### 5.3 GET /api/orders/{orderId}

**Purpose**: View full order details including line items with item details and inventory quantities.

Replaces the monolith's `orderService.getOrder(orderId)` (which loads line items, then for each line item loads the item details and inventory quantity) combined with the authorization check from `OrderActionBean.viewOrder()`.

**Authentication**: Required (JWT Bearer token)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `orderId` | integer | Yes | The order ID to retrieve |

**Success Response** — `200 OK` (`OrderDTO` — full detail):

```json
{
  "orderId": 1000,
  "username": "j2ee",
  "orderDate": "2025-01-15T10:30:00Z",
  "status": "CONFIRMED",
  "shipToFirstName": "ABC",
  "shipToLastName": "XYX",
  "shipAddress1": "901 San Antonio Road",
  "shipAddress2": "MS UCUP02-206",
  "shipCity": "Palo Alto",
  "shipState": "CA",
  "shipZip": "94303",
  "shipCountry": "USA",
  "billToFirstName": "ABC",
  "billToLastName": "XYX",
  "billAddress1": "901 San Antonio Road",
  "billAddress2": "MS UCUP02-206",
  "billCity": "Palo Alto",
  "billState": "CA",
  "billZip": "94303",
  "billCountry": "USA",
  "creditCard": "999 9999 9999 9999",
  "expiryDate": "12/03",
  "cardType": "Visa",
  "courier": "UPS",
  "locale": "CA",
  "totalPrice": 91.50,
  "lineItems": [
    {
      "lineNumber": 1,
      "itemId": "EST-1",
      "quantity": 2,
      "unitPrice": 16.50,
      "totalPrice": 33.00,
      "item": {
        "itemId": "EST-1",
        "productId": "FI-SW-01",
        "listPrice": 16.50,
        "unitCost": 10.00,
        "supplierId": 1,
        "status": "P",
        "attribute1": "Large",
        "attribute2": null,
        "attribute3": null,
        "attribute4": null,
        "attribute5": null,
        "product": {
          "productId": "FI-SW-01",
          "categoryId": "FISH",
          "name": "Angelfish",
          "description": "<image src=\"../images/fish1.gif\">Salt Water fish from Australia"
        },
        "quantity": 9998
      }
    }
  ]
}
```

> **Cross-service data enrichment**: The `lineItems[].item` object is fetched from the Catalog Service via `GET /api/items/{itemId}`. This matches the monolith's `OrderService.getOrder()` behavior which loads item details and inventory quantity for each line item. If the Catalog Service is unavailable, the `item` field may be `null` or contain only the `itemId`.

**Error Responses**:

- `404 Not Found` — Order does not exist:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 404,
    "error": "Not Found",
    "message": "Order not found",
    "path": "/api/orders/9999"
  }
  ```

- `403 Forbidden` — Authenticated user viewing another user's order:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 403,
    "error": "Forbidden",
    "message": "You may only view your own orders.",
    "path": "/api/orders/1000"
  }
  ```

  > **Authorization**: This matches the monolith's `OrderActionBean.viewOrder()` check: `if (accountBean.getAccount().getUsername().equals(order.getUsername()))` — if the usernames don't match, the error message `"You may only view your own orders."` is returned (line 182).

---

### 5.4 GET /api/cart/{sessionId}

**Purpose**: Retrieve the current cart state for a given session.

Replaces the session-scoped `CartActionBean.getCart()` with an externalized, Redis-backed cart store.

**Authentication**: Optional (anonymous carts supported — identified by session cookie value)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `sessionId` | string | Yes | Session identifier — session cookie value for anonymous users, or username for authenticated users |

**Success Response** — `200 OK` (`CartDTO`):

```json
{
  "sessionId": "abc123",
  "items": [
    {
      "itemId": "EST-1",
      "quantity": 2,
      "inStock": true,
      "item": {
        "itemId": "EST-1",
        "productId": "FI-SW-01",
        "listPrice": 16.50,
        "unitCost": 10.00,
        "supplierId": 1,
        "status": "P",
        "attribute1": "Large",
        "attribute2": null,
        "attribute3": null,
        "attribute4": null,
        "attribute5": null,
        "product": {
          "productId": "FI-SW-01",
          "categoryId": "FISH",
          "name": "Angelfish",
          "description": "<image src=\"../images/fish1.gif\">Salt Water fish from Australia"
        },
        "quantity": 10000
      },
      "totalPrice": 33.00
    }
  ],
  "subTotal": 33.00,
  "numberOfItems": 1
}
```

> **Field mapping**: `subTotal` matches `Cart.getSubTotal()` (sum of `item.listPrice * cartItem.quantity` for all items). `numberOfItems` matches `Cart.getNumberOfItems()` (count of distinct items, not total quantity). `inStock` matches the `CartItem.isInStock()` property. `totalPrice` per cart item is `listPrice * quantity`.

**Empty cart**: If no cart exists for the session, an empty cart is returned:

```json
{
  "sessionId": "abc123",
  "items": [],
  "subTotal": 0.00,
  "numberOfItems": 0
}
```

---

### 5.5 POST /api/cart/{sessionId}/items

**Purpose**: Add an item to the cart.

Replaces the monolith's `CartActionBean.addItemToCart()`.

**Authentication**: Optional (anonymous carts supported)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `sessionId` | string | Yes | Session identifier |

**Request Body**:

```json
{
  "itemId": "EST-1"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `itemId` | string | Yes | Catalog item ID to add |

**Success Response** — `200 OK` (`CartDTO`):

Returns the full updated cart (same structure as `GET /api/cart/{sessionId}`).

**Behavior**:

The add-to-cart logic replicates `CartActionBean.addItemToCart()` exactly:

1. If `itemId` is null or empty: return `400 Bad Request`
2. If the item already exists in the cart: increment its quantity by 1 (matching `cart.incrementQuantityByItemId(workingItemId)`)
3. If the item is new to the cart:
   - Fetch stock status from Catalog Service: `GET /api/items/{itemId}/inventory` → `inStock`
   - Fetch item details from Catalog Service: `GET /api/items/{itemId}` → full `ItemDTO`
   - Add the item with quantity 1 and the fetched `inStock` status (matching `catalogService.isItemInStock()` + `catalogService.getItem()` + `cart.addItem(item, isInStock)`)

**Error Response** — `400 Bad Request`:

```json
{
  "timestamp": "2025-01-15T10:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid item ID: cannot add item to cart.",
  "path": "/api/cart/abc123/items"
}
```

> **Error message**: Matches the monolith's `CartActionBean.addItemToCart()` line 71.

---

### 5.6 DELETE /api/cart/{sessionId}/items/{itemId}

**Purpose**: Remove an item from the cart.

Replaces the monolith's `CartActionBean.removeItemFromCart()`.

**Authentication**: Optional (anonymous carts supported)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `sessionId` | string | Yes | Session identifier |
| `itemId` | string | Yes | Item ID to remove from the cart |

**Success Response** — `200 OK` (`CartDTO`):

Returns the full updated cart after removal (same structure as `GET /api/cart/{sessionId}`).

**Error Responses**:

- `404 Not Found` — Item not in cart:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 404,
    "error": "Not Found",
    "message": "Attempted to remove null CartItem from Cart.",
    "path": "/api/cart/abc123/items/EST-999"
  }
  ```

  > **Error message**: Matches the monolith's `CartActionBean.removeItemFromCart()` line 104.

- `400 Bad Request` — Invalid item ID:
  ```json
  {
    "timestamp": "2025-01-15T10:30:00Z",
    "status": 400,
    "error": "Bad Request",
    "message": "Invalid item ID: cannot remove item from cart.",
    "path": "/api/cart/abc123/items/"
  }
  ```

  > **Error message**: Matches the monolith's `CartActionBean.removeItemFromCart()` line 97.

---

### 5.7 PUT /api/cart/{sessionId}

**Purpose**: Update quantities for items in the cart.

Replaces the monolith's `CartActionBean.updateCartQuantities()`.

**Authentication**: Optional (anonymous carts supported)

**Path Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `sessionId` | string | Yes | Session identifier |

**Request Body**:

```json
{
  "updates": [
    { "itemId": "EST-1", "quantity": 3 },
    { "itemId": "EST-14", "quantity": 0 }
  ]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `updates` | array | Yes | List of item quantity updates |
| `updates[].itemId` | string | Yes | Item ID to update |
| `updates[].quantity` | integer | Yes | New quantity |

**Success Response** — `200 OK` (`CartDTO`):

Returns the full updated cart (same structure as `GET /api/cart/{sessionId}`).

**Behavior**:

The quantity update logic replicates `CartActionBean.updateCartQuantities()` exactly:

1. For each update entry, set the item's quantity to the specified value
2. If the new quantity is less than 1 (`quantity < 1`), the item is removed from the cart entirely
3. Invalid numeric values in the update entries are silently ignored (matching the `catch (NumberFormatException e)` block in the monolith)

> **Removal rule**: Setting `quantity` to `0` or any negative value removes the item, matching the monolith's `if (quantity < 1) { cartItems.remove(); }` behavior.

---

## 6. Error Handling Convention

### 6.1 Standard Error Response Format

All error responses across all three services follow a consistent structure:

```json
{
  "timestamp": "2025-01-15T10:30:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Detailed human-readable error description",
  "path": "/api/resource/path"
}
```

| Field | Type | Description |
|-------|------|-------------|
| `timestamp` | string | ISO 8601 timestamp of when the error occurred |
| `status` | integer | HTTP status code |
| `error` | string | HTTP status phrase (e.g., `"Bad Request"`, `"Not Found"`) |
| `message` | string | Human-readable error detail. Where applicable, matches the monolith's original error messages |
| `path` | string | The request URI that triggered the error |

For validation errors (400 Bad Request), an optional `fieldErrors` array may be included:

```json
{
  "timestamp": "2025-01-15T10:30:00.000Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/accounts",
  "fieldErrors": [
    { "field": "firstName", "message": "must not be blank" },
    { "field": "email", "message": "must be a valid email address" }
  ]
}
```

### 6.2 HTTP Status Code Reference

| Status Code | Meaning | When Used |
|------------|---------|-----------|
| `200 OK` | Request processed successfully | Successful GET, PUT, and POST (for signon, cart operations) |
| `201 Created` | Resource successfully created | Successful POST for account creation and order placement |
| `400 Bad Request` | Client-side validation error | Missing required fields, invalid field values, malformed JSON |
| `401 Unauthorized` | Missing or invalid JWT token | Protected endpoints accessed without valid authentication |
| `403 Forbidden` | Authenticated but not authorized | Attempting to view another user's orders, update another user's account |
| `404 Not Found` | Resource does not exist | Account/category/product/item/order not found by ID |
| `409 Conflict` | Business rule violation | Duplicate username on registration, insufficient inventory on order |
| `500 Internal Server Error` | Unexpected server-side error | Unhandled exceptions, database errors |
| `503 Service Unavailable` | Downstream service unreachable | Catalog Service unavailable during order placement (Saga failure) |

---

## 7. Common Headers

### 7.1 Request Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type: application/json` | Yes (for POST/PUT) | All request bodies must be JSON |
| `Authorization: Bearer {jwt_token}` | Conditional | Required for protected endpoints (see Section 2.2) |
| `X-Request-ID: {uuid}` | No | Optional request correlation ID for distributed tracing |
| `X-Idempotency-Key: {orderId}` | Conditional | Required for inventory decrement/restore requests |

### 7.2 Response Headers

| Header | Description |
|--------|-------------|
| `Content-Type: application/json` | All response bodies are JSON |
| `X-Request-ID: {uuid}` | Echoed back if provided in the request |
| `Location: {uri}` | Returned with `201 Created` responses pointing to the created resource |

---

## 8. Cross-Service Communication Patterns

### 8.1 Service Dependency Map

The following diagram shows which services call which endpoints on other services:

```
┌──────────────────────┐
│   API Gateway        │
│                      │─── validates JWT ───► Account Service (POST /api/accounts/signon)
│                      │─── routes to ──────► All three services based on routing flags
└──────────────────────┘

┌──────────────────────┐
│   Account Service    │
│                      │─── GET /api/products?categoryId={id} ──► Catalog Service
│                      │    (for personalization / myList)
│                      │    Fallback: empty list on failure
└──────────────────────┘

┌──────────────────────┐
│   Catalog Service    │
│                      │    No outbound service dependencies
│                      │    (fully self-contained)
└──────────────────────┘

┌──────────────────────┐
│   Order Service      │
│                      │─── GET /api/accounts/{username} ───────► Account Service
│                      │    (verify user exists during order)
│                      │
│                      │─── GET /api/items/{id} ────────────────► Catalog Service
│                      │    (item details for order view)
│                      │
│                      │─── GET /api/items/{id}/inventory ──────► Catalog Service
│                      │    (stock check for cart add)
│                      │
│                      │─── POST /api/items/{id}/inventory/     ► Catalog Service
│                      │    decrement (Saga step: reserve)
│                      │
│                      │─── POST /api/items/{id}/inventory/     ► Catalog Service
│                      │    restore (Saga compensation)
└──────────────────────┘

┌──────────────────────┐
│   Monolith           │
│   (Updated           │─── POST /api/accounts/signon ─────────► Account Service
│    ActionBeans)      │─── GET /api/accounts/{username} ───────► Account Service
│                      │─── POST /api/accounts ─────────────────► Account Service
│                      │─── PUT /api/accounts/{username} ───────► Account Service
│                      │
│                      │─── GET /api/categories ────────────────► Catalog Service
│                      │─── GET /api/categories/{id} ───────────► Catalog Service
│                      │─── GET /api/products?categoryId={id} ──► Catalog Service
│                      │─── GET /api/products/{id} ─────────────► Catalog Service
│                      │─── GET /api/products/search?keywords=  ► Catalog Service
│                      │─── GET /api/items?productId={id} ──────► Catalog Service
│                      │─── GET /api/items/{id} ────────────────► Catalog Service
│                      │
│                      │─── POST /api/orders ───────────────────► Order Service
│                      │─── GET /api/orders?username={u} ───────► Order Service
│                      │─── GET /api/orders/{id} ───────────────► Order Service
│                      │─── GET /api/cart/{sessionId} ──────────► Order Service
│                      │─── POST /api/cart/{sessionId}/items ───► Order Service
│                      │─── DELETE /api/cart/{sid}/items/{iid} ─► Order Service
│                      │─── PUT /api/cart/{sessionId} ──────────► Order Service
└──────────────────────┘
```

### 8.2 Failure Handling and Graceful Degradation

| Calling Service | Called Service | Failure Behavior |
|----------------|---------------|------------------|
| Account Service | Catalog Service (personalization) | `myList` set to empty list; page renders normally without product suggestions; warning logged |
| Order Service | Catalog Service (inventory decrement) | Order status set to `FAILED`; no inventory decremented; user shown error and can retry |
| Order Service | Catalog Service (item details for order view) | `item` field in line items set to `null`; order details shown without product metadata |
| Order Service | Account Service (user verification) | Order rejected with `503 Service Unavailable` |
| Monolith ActionBeans | Any service | Monolith falls back to local service classes if routing flag is set to `"monolith"` |

### 8.3 Saga Sequence Diagram (Order Placement)

```
Client              Order Service           Catalog Service        Order DB
  │                      │                       │                    │
  │─POST /api/orders────►│                       │                    │
  │                      │──INSERT order ────────────────────────────►│ (status=PENDING)
  │                      │──INSERT orderstatus ─────────────────────►│
  │                      │──INSERT lineitems ───────────────────────►│
  │                      │                       │                    │
  │                      │──POST /inventory/     │                    │
  │                      │  decrement (item 1)──►│                    │
  │                      │◄─── 200 OK ──────────│                    │
  │                      │                       │                    │
  │                      │──POST /inventory/     │                    │
  │                      │  decrement (item 2)──►│                    │
  │                      │◄─── 200 OK ──────────│                    │
  │                      │                       │                    │
  │                      │──UPDATE order status ────────────────────►│ (status=CONFIRMED)
  │◄─── 201 Created ────│                       │                    │
  │                      │                       │                    │

  === FAILURE SCENARIO (inventory insufficient for item 2) ===

  │                      │──POST /inventory/     │                    │
  │                      │  decrement (item 2)──►│                    │
  │                      │◄─── 409 Conflict ────│                    │
  │                      │                       │                    │
  │                      │──POST /inventory/     │ (compensate)       │
  │                      │  restore (item 1) ───►│                    │
  │                      │◄─── 200 OK ──────────│                    │
  │                      │                       │                    │
  │                      │──UPDATE order status ────────────────────►│ (status=FAILED)
  │◄─── 409 Conflict ───│                       │                    │
```

### 8.4 Cutover Order

Services are cut over from the monolith in the following order, justified by dependency analysis:

1. **Catalog Service (First)** — Lowest risk; predominantly read-only; no outbound dependencies; validates infrastructure patterns
2. **Account Service (Second)** — Medium risk; self-contained with one outbound dependency on Catalog Service (already live); validates JWT authentication
3. **Order Service (Last)** — Highest risk; depends on both Catalog and Account services; requires Saga orchestration; benefits from lessons learned

---

*This document is the canonical source of truth for all service-to-service and client-to-service API contracts in the JPetStore microservices architecture. All implementations must conform to these specifications.*
