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
package com.jpetstore.order.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Data Transfer Object representing the externalized cart state for the
 * Order Service's cart REST API.
 *
 * <p>This DTO is the response/request body for the cart REST endpoints
 * exposed by {@code CartController}:</p>
 * <ul>
 *   <li>{@code GET /api/cart/{sessionId}} — Returns the full cart contents</li>
 *   <li>{@code POST /api/cart/{sessionId}/items} — Adds an item (uses {@link CartItemDTO})</li>
 *   <li>{@code PUT /api/cart/{sessionId}} — Bulk update (replaces entire cart)</li>
 *   <li>{@code DELETE /api/cart/{sessionId}/items/{itemId}} — Removes an item</li>
 * </ul>
 *
 * <p>The cart state is externalized from the monolith's HTTP session-scoped
 * {@code CartActionBean} into a Redis-backed store (see
 * {@link com.jpetstore.order.entity.CartState}). This DTO provides a
 * transport-layer representation that decouples the REST API contract from
 * the persistence model.</p>
 *
 * <h3>Cart Identification</h3>
 * <p>The cart is identified by a {@code sessionId}:</p>
 * <ul>
 *   <li>For anonymous users: a cookie-based session identifier</li>
 *   <li>For authenticated users: the username (merged on login)</li>
 * </ul>
 *
 * <h3>Subtotal Calculation</h3>
 * <p>The {@code subTotal} field is computed server-side as the sum of
 * {@code quantity × unitPrice} for all items in the cart. It is included
 * in the response for convenience but is not accepted on input — the server
 * always recalculates it.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>This is a simple POJO/DTO — not thread-safe. Concurrent cart operations
 * are serialized at the service layer through Redis atomic operations.</p>
 *
 * @see CartItemDTO
 * @see com.jpetstore.order.entity.CartState
 * @see com.jpetstore.order.service.CartStateService
 */
public class CartDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /**
     * The session identifier that uniquely identifies this cart.
     *
     * <p>For anonymous users, this is a generated session cookie value.
     * For authenticated users, this is the username (the anonymous cart
     * is merged into the user-keyed cart on login).</p>
     */
    private String sessionId;

    /**
     * The list of items currently in the cart.
     *
     * <p>Each entry represents one product item with its quantity, price, and
     * computed total. An empty list indicates an empty cart (not null).
     * The list order is maintained for consistent display.</p>
     */
    private List<CartItemDTO> items;

    /**
     * The computed subtotal of all items in the cart.
     *
     * <p>Calculated as the sum of {@code item.total} (which is
     * {@code item.quantity × item.unitPrice}) for all items in the
     * {@link #items} list. This value is always computed server-side
     * and included in responses for convenience.</p>
     */
    private BigDecimal subTotal;

    /**
     * The total number of distinct items in the cart.
     *
     * <p>This equals {@code items.size()}, included for convenience
     * so that API consumers don't need to count the list themselves.</p>
     */
    private int numberOfItems;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-arg constructor for JSON deserialization and general use.
     * Initializes items to an empty list and subTotal to zero.
     */
    public CartDTO() {
        this.items = new ArrayList<>();
        this.subTotal = BigDecimal.ZERO;
        this.numberOfItems = 0;
    }

    /**
     * Constructs a CartDTO with all fields.
     *
     * @param sessionId     the session identifier for this cart
     * @param items         the list of cart items
     * @param subTotal      the computed subtotal
     * @param numberOfItems the number of distinct items
     */
    public CartDTO(String sessionId, List<CartItemDTO> items, BigDecimal subTotal, int numberOfItems) {
        this.sessionId = sessionId;
        this.items = items != null ? new ArrayList<>(items) : new ArrayList<>();
        this.subTotal = subTotal != null ? subTotal : BigDecimal.ZERO;
        this.numberOfItems = numberOfItems;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the session identifier for this cart.
     *
     * @return the session ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Sets the session identifier for this cart.
     *
     * @param sessionId the session ID to set
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Returns the list of items in the cart.
     *
     * @return the cart items (never null)
     */
    public List<CartItemDTO> getItems() {
        return items;
    }

    /**
     * Sets the list of items in the cart.
     *
     * @param items the cart items to set
     */
    public void setItems(List<CartItemDTO> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    /**
     * Returns the computed subtotal of all cart items.
     *
     * @return the subtotal as {@link BigDecimal}
     */
    public BigDecimal getSubTotal() {
        return subTotal;
    }

    /**
     * Sets the subtotal of all cart items.
     *
     * @param subTotal the subtotal to set
     */
    public void setSubTotal(BigDecimal subTotal) {
        this.subTotal = subTotal;
    }

    /**
     * Returns the number of distinct items in the cart.
     *
     * @return the item count
     */
    public int getNumberOfItems() {
        return numberOfItems;
    }

    /**
     * Sets the number of distinct items in the cart.
     *
     * @param numberOfItems the item count to set
     */
    public void setNumberOfItems(int numberOfItems) {
        this.numberOfItems = numberOfItems;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Two CartDTOs are equal if they have the same sessionId.
     *
     * @param o the object to compare with
     * @return {@code true} if the objects represent the same cart
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        CartDTO cartDTO = (CartDTO) o;
        return Objects.equals(sessionId, cartDTO.sessionId);
    }

    /**
     * Hash code based on sessionId.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(sessionId);
    }

    /**
     * Returns a string representation of this cart DTO for debugging.
     *
     * @return a string containing the cart's key fields
     */
    @Override
    public String toString() {
        return "CartDTO{"
                + "sessionId='" + sessionId + '\''
                + ", numberOfItems=" + numberOfItems
                + ", subTotal=" + subTotal
                + ", items=" + (items != null ? items.size() : 0) + " item(s)"
                + '}';
    }
}
