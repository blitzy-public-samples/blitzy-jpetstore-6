/*
 *    Copyright 2010-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.jpetstore.order.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

/**
 * Redis hash model for externalized cart state in the Order Service.
 *
 * <p>This class replaces the monolith's HTTP session-scoped {@code Cart} object
 * (managed by {@code CartActionBean} with {@code @SessionScope}). In the monolith,
 * the cart is stored in-memory using {@code Collections.synchronizedMap(new HashMap<>())}
 * within a single JVM. In the microservices architecture, the cart state is externalized
 * to Redis so that:
 * <ul>
 *   <li>The Order Service can manage cart data statelessly across multiple instances</li>
 *   <li>Cart state survives server restarts and load-balanced request routing</li>
 *   <li>Anonymous users (identified by session cookie) and authenticated users
 *       (identified by username) can maintain persistent carts</li>
 * </ul>
 *
 * <p>The cart is stored under the Redis key prefix {@code "cart"}, with the full key
 * being {@code cart:<id>} where {@code id} is either a session cookie value (for
 * anonymous users) or a username (for authenticated users). On login, the anonymous
 * cart can be merged into the user's persistent cart — preserving the existing monolith
 * behavior where unauthenticated users can browse and build a cart before signing in.
 *
 * <p>Key differences from the monolith {@code Cart.java}:
 * <ul>
 *   <li>No synchronization wrapper — Redis handles concurrent access atomically</li>
 *   <li>No parallel {@code ArrayList} — the {@code Map} provides sufficient ordering
 *       (uses {@code HashMap} initialization; switch to {@code LinkedHashMap} if
 *       insertion order matters)</li>
 *   <li>No {@code getSubTotal()} method — subtotal computation belongs in
 *       {@code CartStateService}, not in the entity</li>
 *   <li>Cart items store denormalized data (itemId, unitPrice, productName, inStock)
 *       instead of full Catalog domain objects, since Item lives in the Catalog Service's
 *       separate database</li>
 * </ul>
 *
 * <p><strong>Cross-service data isolation:</strong> This entity does NOT reference or
 * import any Catalog Service entities. Item details (price, name) are denormalized into
 * {@link CartItemData} to avoid cross-service calls for simple cart display operations.
 *
 * @see CartItemData
 */
@RedisHash("cart")
public class CartState implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The Redis key for this cart state entry.
     *
     * <p>Represents either a session ID (for anonymous/unauthenticated users) or a
     * username (for authenticated users). Per the Strangler Fig coexistence design,
     * the cart is keyed by a session cookie for anonymous users or by username for
     * authenticated users.
     */
    @Id
    private String id;

    /**
     * Map of cart items keyed by item ID.
     *
     * <p>Replaces the monolith's {@code Map<String, CartItem> itemMap} from
     * {@code Cart.java}. The key is the item ID (e.g., "EST-1"), and the value
     * is a {@link CartItemData} containing denormalized item details needed for
     * cart display and order creation.
     *
     * <p>Initialized to an empty {@code HashMap} to avoid {@code NullPointerException}
     * on first access. Unlike the monolith, no {@code Collections.synchronizedMap()}
     * wrapper is needed because Redis handles concurrency at the data store level.
     */
    private Map<String, CartItemData> items = new HashMap<>();

    /**
     * Timestamp of the last modification to this cart state.
     *
     * <p>Used for cache management (TTL-based eviction of abandoned carts) and
     * dual-write tracking during the Strangler Fig coexistence window to ensure
     * consistency between Redis and the monolith's session state.
     */
    private Instant lastUpdated;

    /**
     * Default no-arg constructor required for Spring Data Redis deserialization.
     */
    public CartState() {
        // No-arg constructor for Redis deserialization
    }

    /**
     * Returns the Redis key for this cart state.
     *
     * @return the cart identifier (session ID or username)
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the Redis key for this cart state.
     *
     * @param id the cart identifier (session ID or username)
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the map of cart items keyed by item ID.
     *
     * @return the items map, never {@code null} (initialized to empty map)
     */
    public Map<String, CartItemData> getItems() {
        return items;
    }

    /**
     * Sets the map of cart items.
     *
     * @param items the items map; if {@code null}, subsequent access may throw
     *              {@code NullPointerException} — callers should pass an empty map instead
     */
    public void setItems(Map<String, CartItemData> items) {
        this.items = items;
    }

    /**
     * Returns the timestamp of the last modification to this cart state.
     *
     * @return the last updated instant, or {@code null} if never set
     */
    public Instant getLastUpdated() {
        return lastUpdated;
    }

    /**
     * Sets the timestamp of the last modification to this cart state.
     *
     * @param lastUpdated the instant when the cart was last modified
     */
    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    /**
     * Denormalized cart item data for Redis storage.
     *
     * <p>In the monolith, {@code CartItem} holds a direct reference to the full
     * {@code Item} domain object (which includes product details, supplier info,
     * and inventory data). In the microservices architecture, the {@code Item}
     * entity lives in the Catalog Service's separate PostgreSQL database and is
     * not directly accessible from the Order Service.
     *
     * <p>This inner class stores only the subset of item data needed for:
     * <ul>
     *   <li>Cart display (product name, unit price, in-stock status)</li>
     *   <li>Order creation (item ID, unit price, quantity)</li>
     *   <li>Subtotal computation (unit price × quantity, computed in service layer)</li>
     * </ul>
     *
     * <p>Field mapping from monolith:
     * <ul>
     *   <li>{@code CartItem.getItem().getItemId()} → {@code itemId}</li>
     *   <li>{@code CartItem.getQuantity()} → {@code quantity}</li>
     *   <li>{@code CartItem.isInStock()} → {@code inStock}</li>
     *   <li>{@code CartItem.getItem().getListPrice()} → {@code unitPrice}</li>
     *   <li>{@code CartItem.getItem()} product name → {@code productName}</li>
     * </ul>
     *
     * <p><strong>Note:</strong> The monolith's {@code CartItem.calculateTotal()}
     * and {@code Cart.getSubTotal()} computations are NOT stored here — they are
     * computed on the fly by {@code CartStateService} when needed.
     */
    public static class CartItemData implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * The unique identifier of the catalog item (e.g., "EST-1", "EST-14").
         *
         * <p>Corresponds to {@code CartItem.getItem().getItemId()} in the monolith.
         * This is a denormalized reference — the full item entity resides in the
         * Catalog Service's database. Cross-service lookups use this ID to call
         * the Catalog Service REST API when additional item details are needed.
         */
        private String itemId;

        /**
         * The quantity of this item in the cart.
         *
         * <p>Corresponds to {@code CartItem.getQuantity()} in the monolith (line 32).
         * Must be a positive integer; validation is enforced in {@code CartStateService}.
         */
        private int quantity;

        /**
         * Whether this item is currently in stock in the Catalog Service's inventory.
         *
         * <p>Corresponds to {@code CartItem.isInStock()} in the monolith (line 33).
         * This value is denormalized at the time the item is added to the cart and
         * may become stale if inventory changes. The service layer should refresh
         * this value before order placement.
         */
        private boolean inStock;

        /**
         * The unit price (list price) of the item at the time it was added to the cart.
         *
         * <p>Corresponds to {@code CartItem.getItem().getListPrice()} in the monolith.
         * Stored as {@code BigDecimal} to preserve exact monetary precision. This is
         * the price used for subtotal calculations ({@code unitPrice × quantity}).
         *
         * <p>Price is denormalized to avoid cross-service calls to the Catalog Service
         * for every cart display operation. If the Catalog Service updates the price,
         * the cart retains the price at the time of addition.
         */
        private BigDecimal unitPrice;

        /**
         * The display name of the product associated with this item.
         *
         * <p>Cached from the Catalog Service at the time the item is added to the cart
         * to enable cart display without making cross-service REST calls. This avoids
         * the need to call {@code GET /api/items/{id}} on the Catalog Service for
         * every cart page render.
         */
        private String productName;

        /**
         * Default no-arg constructor required for Redis deserialization.
         */
        public CartItemData() {
            // No-arg constructor for Redis deserialization
        }

        /**
         * Returns the item ID.
         *
         * @return the catalog item identifier
         */
        public String getItemId() {
            return itemId;
        }

        /**
         * Sets the item ID.
         *
         * @param itemId the catalog item identifier
         */
        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        /**
         * Returns the quantity of this item in the cart.
         *
         * @return the item quantity
         */
        public int getQuantity() {
            return quantity;
        }

        /**
         * Sets the quantity of this item in the cart.
         *
         * @param quantity the item quantity
         */
        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        /**
         * Returns whether this item is in stock.
         *
         * @return {@code true} if the item is in stock, {@code false} otherwise
         */
        public boolean isInStock() {
            return inStock;
        }

        /**
         * Sets the in-stock status of this item.
         *
         * @param inStock {@code true} if the item is in stock, {@code false} otherwise
         */
        public void setInStock(boolean inStock) {
            this.inStock = inStock;
        }

        /**
         * Returns the unit price of this item.
         *
         * @return the unit price as a {@code BigDecimal}
         */
        public BigDecimal getUnitPrice() {
            return unitPrice;
        }

        /**
         * Sets the unit price of this item.
         *
         * @param unitPrice the unit price as a {@code BigDecimal}
         */
        public void setUnitPrice(BigDecimal unitPrice) {
            this.unitPrice = unitPrice;
        }

        /**
         * Returns the product name for display purposes.
         *
         * @return the cached product name
         */
        public String getProductName() {
            return productName;
        }

        /**
         * Sets the product name for display purposes.
         *
         * @param productName the product name to cache
         */
        public void setProductName(String productName) {
            this.productName = productName;
        }
    }
}
