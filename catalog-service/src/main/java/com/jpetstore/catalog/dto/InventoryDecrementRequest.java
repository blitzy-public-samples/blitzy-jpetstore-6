package com.jpetstore.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for the {@code POST /api/items/{id}/inventory/decrement} endpoint.
 *
 * <p>Used by the Order Service during the distributed order transaction (Saga pattern)
 * to request inventory reservation/decrement for each line item. In the original monolith,
 * this operation was performed via
 * {@code ItemMapper.updateInventoryQuantity(Map<"itemId","increment">)} within a single
 * {@code @Transactional} boundary in {@code OrderService.insertOrder()}. In the decomposed
 * architecture, the Order Service calls the Catalog Service's REST API with this DTO.
 *
 * <p>The {@link #orderId} field serves as an <strong>idempotency key</strong> for the Saga
 * pattern. The Catalog Service stores reservation records indexed by {@code orderId} and
 * returns success for duplicate requests without double-decrementing inventory. This
 * guarantees that retried requests from the Order Service's Saga orchestrator do not
 * cause over-decrement of stock quantities.
 *
 * <p>The {@link #quantity} field maps to the monolith's {@code increment} parameter in the
 * MyBatis SQL: {@code UPDATE INVENTORY SET QTY = QTY - #{increment} WHERE ITEMID = #{itemId}}.
 * It must be at least 1 because the monolith always decrements by the cart item quantity,
 * which is always &ge; 1.
 *
 * <p>Validation is triggered when the controller receives this DTO annotated with
 * {@code @Valid}, causing Spring Boot to return {@code 400 Bad Request} automatically
 * if any constraint is violated.
 *
 * @see com.jpetstore.catalog.controller.ItemController
 * @see com.jpetstore.catalog.service.InventoryService
 */
public class InventoryDecrementRequest {

    /**
     * The number of items to decrement from inventory.
     *
     * <p>Must be a non-null positive integer (minimum value of 1). Uses the boxed
     * {@link Integer} type so that {@code @NotNull} can detect missing values — a
     * primitive {@code int} would default to 0 and bypass the null check.
     *
     * <p>Maps to the monolith's {@code increment} parameter in
     * {@code ItemMapper.updateInventoryQuantity(Map<"itemId","increment">)}.
     */
    @NotNull(message = "Quantity must not be null")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    /**
     * The order identifier used as an idempotency key for the Saga pattern.
     *
     * <p>Every inventory reservation request includes the {@code orderId} so that
     * the Catalog Service can detect and safely handle duplicate requests. If a
     * reservation with the same {@code orderId} has already been processed, the
     * service returns success without decrementing again. This prevents
     * double-decrementing when the Order Service's Saga orchestrator retries a
     * failed step.
     *
     * <p>Validated with {@code @NotBlank} to ensure the value is not null, not
     * empty, and not whitespace-only.
     */
    @NotBlank(message = "Order ID must not be blank")
    private String orderId;

    /**
     * Returns the number of items to decrement from inventory.
     *
     * @return the quantity to decrement, guaranteed to be &ge; 1 when validation passes
     */
    public Integer getQuantity() {
        return quantity;
    }

    /**
     * Sets the number of items to decrement from inventory.
     *
     * @param quantity the quantity to decrement; must be a non-null positive integer (&ge; 1)
     */
    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    /**
     * Returns the order identifier used as the Saga idempotency key.
     *
     * @return the order ID, guaranteed to be non-blank when validation passes
     */
    public String getOrderId() {
        return orderId;
    }

    /**
     * Sets the order identifier used as the Saga idempotency key.
     *
     * @param orderId the order ID; must not be null, empty, or whitespace-only
     */
    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }
}
