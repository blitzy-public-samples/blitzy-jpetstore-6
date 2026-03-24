package com.jpetstore.order.saga;

/**
 * Defines the discrete steps in the orchestration-based Saga for the distributed
 * order transaction. Each enum constant corresponds to one atomic operation in the
 * saga sequence that replaces the monolith's single {@code @Transactional} boundary
 * in {@code OrderService.insertOrder()}.
 *
 * <p><strong>Saga Execution Order:</strong></p>
 * <ol>
 *   <li>{@link #CREATE_ORDER} — Local write: insert order (PENDING), orderstatus, lineitems</li>
 *   <li>{@link #RESERVE_INVENTORY} — Cross-service: decrement inventory via Catalog Service REST API</li>
 *   <li>{@link #CONFIRM_ORDER} — Local write: update order status from PENDING to CONFIRMED</li>
 * </ol>
 *
 * <p><strong>Critical Design Decision — Step Ordering Change from Monolith:</strong></p>
 * <p>In the monolith, inventory is decremented <em>before</em> the order is written
 * (lines 62–69 before lines 71–76 in {@code OrderService.insertOrder()}). In the saga,
 * this order is <em>reversed</em>: the order record is written first in PENDING state so
 * that there is always a durable record of the attempt. This prevents "phantom decrements"
 * where inventory is reserved but no order record exists.</p>
 *
 * <p>These enum values are persisted in the {@code order_saga_state.current_step} column
 * via {@code @Enumerated(EnumType.STRING)}, so the constant names must remain stable
 * and must not be renamed without a corresponding database migration.</p>
 *
 * @see com.jpetstore.order.saga.OrderSagaState
 * @see com.jpetstore.order.service.OrderSagaOrchestrator
 */
public enum OrderSagaStep {

    /**
     * Step 1: Create the order locally in PENDING state.
     *
     * <p>Writes order, orderstatus, and lineitem records to the Order Service's
     * PostgreSQL database. This step involves only local database writes — no
     * cross-service communication occurs.</p>
     *
     * <p><strong>On failure:</strong> The saga immediately transitions to FAILED
     * status. No compensation is needed because no cross-service side effects
     * have occurred.</p>
     *
     * <p><strong>Monolith reference:</strong> Corresponds to
     * {@code OrderService.insertOrder()} lines 71–76 (orderMapper.insertOrder,
     * orderMapper.insertOrderStatus, lineItemMapper.insertLineItem), but executed
     * <em>before</em> inventory decrement in the saga (reversed from monolith order).</p>
     */
    CREATE_ORDER,

    /**
     * Step 2: Reserve inventory via the Catalog Service REST API.
     *
     * <p>For each line item in the order, calls
     * {@code POST /api/items/{id}/inventory/decrement} on the Catalog Service.
     * Each request includes the {@code orderId} as an idempotency key to prevent
     * double-decrementing on retries.</p>
     *
     * <p><strong>On failure:</strong></p>
     * <ul>
     *   <li>Insufficient stock (HTTP 409): saga transitions to FAILED — order
     *       remains in PENDING and no inventory was decremented for that item.</li>
     *   <li>Partial success (some items decremented, then failure):
     *       {@code InventoryCompensation.compensate()} restores already-decremented
     *       items via {@code POST /api/items/{id}/inventory/restore}.</li>
     *   <li>Catalog Service unavailable (HTTP 5xx): saga status stays PENDING
     *       for reconciliation by a scheduled job that queries sagas past a
     *       configurable timeout threshold.</li>
     * </ul>
     *
     * <p><strong>Monolith reference:</strong> Corresponds to
     * {@code OrderService.insertOrder()} lines 62–69
     * ({@code itemMapper.updateInventoryQuantity(param)} loop).</p>
     */
    RESERVE_INVENTORY,

    /**
     * Step 3: Confirm the order after successful inventory reservation.
     *
     * <p>Updates the order status from PENDING to CONFIRMED in the Order Service's
     * PostgreSQL database. This is the final step in the saga — once completed,
     * the distributed transaction is considered committed. This step involves only
     * a local database update — no cross-service communication occurs.</p>
     *
     * <p><strong>On failure:</strong> If this step fails after inventory has been
     * successfully reserved, a scheduled reconciliation job detects orders that have
     * been in PENDING status beyond a configurable threshold and checks with the
     * Catalog Service whether a reservation exists. If a reservation is confirmed,
     * the order is confirmed; otherwise, it is marked as FAILED.</p>
     *
     * <p><strong>Monolith reference:</strong> No direct equivalent in the monolith.
     * In the monolith, the order is immediately committed as part of the single
     * ACID transaction. In the saga, this explicit confirmation step is required
     * to close the distributed transaction after the cross-service inventory
     * reservation succeeds.</p>
     */
    CONFIRM_ORDER
}
