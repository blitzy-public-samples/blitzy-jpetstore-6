/*
 * CartItemDTO.java — Individual Cart Item Data Transfer Object
 *
 * Part of the Order Service in the JPetStore microservices decomposition.
 * Represents a single item within a shopping cart for the cart REST API.
 *
 * Maps from the externalized cart state (CartState.CartItemData) and corresponds
 * to the monolith's CartItem.java domain class, with cross-service entity
 * references (Item) replaced by denormalized scalar fields (itemId, unitPrice).
 */
package com.jpetstore.order.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * Data Transfer Object representing a single item in the shopping cart.
 *
 * <p>This DTO is used in the Order Service cart REST API responses and is
 * contained within {@code CartDTO.items}. It carries the minimal set of
 * fields needed for cart display and order placement without referencing
 * Catalog Service entities directly.</p>
 *
 * <h3>Field Mapping from Monolith</h3>
 * <ul>
 *   <li>{@code itemId} — from {@code CartItem.getItem().getItemId()} (denormalized)</li>
 *   <li>{@code quantity} — from {@code CartItem.quantity}</li>
 *   <li>{@code inStock} — from {@code CartItem.inStock}</li>
 *   <li>{@code unitPrice} — from {@code CartItem.getItem().getListPrice()} (denormalized)</li>
 *   <li>{@code total} — from {@code CartItem.total} (unitPrice × quantity)</li>
 * </ul>
 *
 * <p>All monetary fields use {@link BigDecimal} for precise arithmetic,
 * matching the monolith's pattern and avoiding floating-point rounding errors.</p>
 *
 * @see java.io.Serializable
 */
public class CartItemDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The unique identifier of the catalog item (e.g., "EST-1", "EST-14").
     * In the monolith, this comes from {@code Item.itemId}. In the microservice
     * architecture, it is a denormalized string key stored in the externalized
     * cart state, avoiding a cross-service entity reference to the Catalog Service.
     */
    private String itemId;

    /**
     * The number of units of this item currently in the cart.
     * Matches the monolith's {@code CartItem.quantity} field.
     */
    private int quantity;

    /**
     * Whether this item is currently in stock in the catalog inventory.
     * In the monolith, this is set by {@code Cart.addItem()} based on the
     * inventory check from {@code CatalogService}. In the microservice,
     * it is populated by querying the Catalog Service's inventory endpoint.
     */
    private boolean inStock;

    /**
     * The per-unit price of this item, matching the item's list price from
     * the Catalog Service. Uses {@link BigDecimal} for precise monetary
     * arithmetic, consistent with the monolith's {@code Item.listPrice} field.
     */
    private BigDecimal unitPrice;

    /**
     * The total price for this cart line (unitPrice × quantity).
     * Uses {@link BigDecimal} for precise monetary arithmetic, matching the
     * monolith's {@code CartItem.calculateTotal()} computation semantics:
     * {@code item.getListPrice().multiply(new BigDecimal(quantity))}.
     */
    private BigDecimal total;

    /**
     * Default no-argument constructor required for JSON deserialization
     * by Jackson and other serialization frameworks.
     */
    public CartItemDTO() {
        // No-arg constructor for JSON deserialization
    }

    /**
     * Returns the unique identifier of the catalog item.
     *
     * @return the item ID string (e.g., "EST-1", "EST-14"), or {@code null}
     *         if not set
     */
    public String getItemId() {
        return itemId;
    }

    /**
     * Sets the unique identifier of the catalog item.
     *
     * @param itemId the item ID string (e.g., "EST-1", "EST-14")
     */
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    /**
     * Returns the quantity of this item in the cart.
     *
     * @return the quantity (number of units)
     */
    public int getQuantity() {
        return quantity;
    }

    /**
     * Sets the quantity of this item in the cart.
     *
     * @param quantity the quantity (number of units)
     */
    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    /**
     * Returns whether this item is currently in stock.
     * Uses the JavaBean convention {@code isInStock()} for boolean getters,
     * matching the monolith's {@code CartItem.isInStock()} method.
     *
     * @return {@code true} if the item is in stock, {@code false} otherwise
     */
    public boolean isInStock() {
        return inStock;
    }

    /**
     * Sets whether this item is currently in stock.
     *
     * @param inStock {@code true} if the item is in stock, {@code false} otherwise
     */
    public void setInStock(boolean inStock) {
        this.inStock = inStock;
    }

    /**
     * Returns the per-unit price of this item.
     *
     * @return the unit price as {@link BigDecimal}, or {@code null} if not set
     */
    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    /**
     * Sets the per-unit price of this item.
     *
     * @param unitPrice the unit price as {@link BigDecimal}
     */
    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    /**
     * Returns the total price for this cart line (unitPrice × quantity).
     *
     * @return the total as {@link BigDecimal}, or {@code null} if not set
     */
    public BigDecimal getTotal() {
        return total;
    }

    /**
     * Sets the total price for this cart line.
     *
     * @param total the total price as {@link BigDecimal}
     */
    public void setTotal(BigDecimal total) {
        this.total = total;
    }

}
