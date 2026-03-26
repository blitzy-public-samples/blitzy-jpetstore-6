/*
 * CartDTO.java — Cart State Data Transfer Object for REST API Responses
 *
 * Part of the Order Service in the JPetStore microservices decomposition.
 * Represents the externalized cart state returned by the cart REST API
 * endpoints (GET /api/cart/{sessionId}, PUT /api/cart/{sessionId}, etc.).
 *
 * Maps from the CartState Redis entity and its CartItemData entries. In the
 * monolith, cart state lived in a session-scoped CartActionBean backed by
 * Cart.java's synchronized HashMap. In the microservice architecture, cart
 * state is externalized to Redis and exposed via this DTO through REST.
 *
 * Field mapping from monolith Cart.java:
 *   id            → cart key (sessionId for anonymous, username for authenticated)
 *   items         → Cart.getCartItemList() decomposed into List<CartItemDTO>
 *   subTotal      → Cart.getSubTotal() — BigDecimal sum of item totals
 *   numberOfItems → Cart.getNumberOfItems() — items.size()
 */
package com.jpetstore.order.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
 * <h3>Cart Identification</h3>
 * <p>The cart is identified by an {@code id} field which serves as the cart key:</p>
 * <ul>
 *   <li>For anonymous users: a cookie-based session identifier</li>
 *   <li>For authenticated users: the username (anonymous cart merged on login)</li>
 * </ul>
 *
 * <h3>Subtotal Calculation</h3>
 * <p>The {@code subTotal} field is computed server-side by
 * {@code CartStateService} as the sum of {@code quantity × unitPrice} for all
 * items in the cart, matching the monolith's {@code Cart.getSubTotal()} which
 * uses {@code BigDecimal::add} reduction. Uses {@link BigDecimal} for precise
 * monetary arithmetic — never {@code double} or {@code float}.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>This is a simple POJO/DTO — not thread-safe. Concurrent cart operations
 * are serialized at the service layer through Redis atomic operations.</p>
 *
 * @see CartItemDTO
 */
public class CartDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------

    /**
     * The unique identifier for this cart — serves as the cart key.
     *
     * <p>For anonymous users, this is a generated session cookie value.
     * For authenticated users, this is the username (the anonymous cart
     * is merged into the user-keyed cart on login). Corresponds to
     * {@code CartState.id} in the Redis persistence layer.</p>
     */
    private String id;

    /**
     * The list of items currently in the cart.
     *
     * <p>Each entry represents one product item with its quantity, price, and
     * computed total. An empty list indicates an empty cart (never null).
     * The list order is maintained for consistent display, matching the
     * monolith's {@code Cart.getCartItemList()} ordering.</p>
     *
     * <p>Initialized to an empty {@link ArrayList} to prevent
     * {@link NullPointerException} during serialization and iteration,
     * matching the monolith's {@code Cart.java} pattern (line 37).</p>
     */
    private List<CartItemDTO> items = new ArrayList<>();

    /**
     * The computed subtotal of all items in the cart.
     *
     * <p>Calculated as the sum of {@code item.getTotal()} (which is
     * {@code item.unitPrice × item.quantity}) for all items in the
     * {@link #items} list. This value is always computed server-side by
     * {@code CartStateService} and included in responses for convenience.</p>
     *
     * <p>Uses {@link BigDecimal} for precise monetary arithmetic, matching
     * the monolith's {@code Cart.getSubTotal()} (lines 119-123) which uses
     * {@code BigDecimal::add} reduction. Never {@code double} or
     * {@code float} per AAP design constraints.</p>
     */
    private BigDecimal subTotal;

    /**
     * The total number of distinct items in the cart.
     *
     * <p>This equals {@code items.size()}, included for convenience
     * so that API consumers don't need to count the list themselves.
     * Matches the monolith's {@code Cart.getNumberOfItems()} (lines 47-49).</p>
     */
    private int numberOfItems;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default no-argument constructor required for JSON deserialization
     * by Jackson and other serialization frameworks.
     *
     * <p>Initializes the items list to an empty {@link ArrayList} and
     * subTotal to {@link BigDecimal#ZERO} to prevent null-related issues
     * during serialization and downstream processing.</p>
     */
    public CartDTO() {
        this.items = new ArrayList<>();
        this.subTotal = BigDecimal.ZERO;
        this.numberOfItems = 0;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the unique identifier for this cart.
     *
     * <p>This is the cart key — a session cookie value for anonymous users,
     * or the username for authenticated users.</p>
     *
     * @return the cart identifier, or {@code null} if not set
     */
    public String getId() {
        return id;
    }

    /**
     * Sets the unique identifier for this cart.
     *
     * @param id the cart identifier (session cookie or username)
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the list of items in the cart.
     *
     * <p>The returned list is never null. An empty list indicates an
     * empty cart. Each {@link CartItemDTO} contains the item's identifier,
     * quantity, stock status, unit price, and computed total.</p>
     *
     * @return the cart items (never null)
     */
    public List<CartItemDTO> getItems() {
        return items;
    }

    /**
     * Sets the list of items in the cart.
     *
     * <p>If {@code null} is passed, the items list is reset to an empty
     * {@link ArrayList} to maintain the non-null invariant.</p>
     *
     * @param items the cart items to set
     */
    public void setItems(List<CartItemDTO> items) {
        this.items = items != null ? items : new ArrayList<>();
    }

    /**
     * Returns the computed subtotal of all cart items.
     *
     * <p>This is the sum of {@code CartItemDTO.getTotal()} across all items
     * in the cart, computed server-side by {@code CartStateService}. Uses
     * {@link BigDecimal} for precise monetary representation.</p>
     *
     * @return the subtotal as {@link BigDecimal}, or {@code null} if not set
     */
    public BigDecimal getSubTotal() {
        return subTotal;
    }

    /**
     * Sets the subtotal of all cart items.
     *
     * <p>This value should be computed by the service layer as the sum of
     * all item totals. The DTO does not perform this computation itself —
     * it is a pure data container.</p>
     *
     * @param subTotal the subtotal to set as {@link BigDecimal}
     */
    public void setSubTotal(BigDecimal subTotal) {
        this.subTotal = subTotal;
    }

    /**
     * Returns the number of distinct items in the cart.
     *
     * <p>Matches the monolith's {@code Cart.getNumberOfItems()} which
     * returns {@code itemList.size()}.</p>
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
    // toString
    // -----------------------------------------------------------------------

    /**
     * Returns a string representation of this cart DTO for debugging and
     * logging purposes. Includes the cart id, item count, and subtotal.
     *
     * @return a human-readable string containing the cart's key fields
     */
    @Override
    public String toString() {
        return "CartDTO{"
                + "id='" + id + '\''
                + ", numberOfItems=" + numberOfItems
                + ", subTotal=" + subTotal
                + ", items=" + (items != null ? items.size() : 0) + " item(s)"
                + '}';
    }

}
