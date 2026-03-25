/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jpetstore.catalog.service;

import java.util.Optional;

import com.jpetstore.catalog.entity.Inventory;
import com.jpetstore.catalog.entity.InventoryReservation;
import com.jpetstore.catalog.repository.InventoryRepository;
import com.jpetstore.catalog.repository.InventoryReservationRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service class encapsulating atomic inventory management operations for the Catalog Service.
 *
 * <p>This service has <strong>no direct counterpart</strong> in the original JPetStore monolith.
 * In the monolith, inventory decrement was performed inline within
 * {@code OrderService.insertOrder()} (a single-process {@code @Transactional} method) via:
 * <pre>
 *   // OrderService.insertOrder(), lines 62-68:
 *   order.getLineItems().forEach(lineItem -&gt; {
 *       Map&lt;String, Object&gt; param = new HashMap&lt;&gt;(2);
 *       param.put("itemId", lineItem.getItemId());
 *       param.put("increment", lineItem.getQuantity());
 *       itemMapper.updateInventoryQuantity(param);
 *   });
 * </pre>
 * and the underlying MyBatis SQL in {@code ItemMapper.xml} (lines 76-80):
 * <pre>
 *   UPDATE INVENTORY SET QTY = QTY - #{increment} WHERE ITEMID = #{itemId}
 * </pre>
 *
 * <p>In the decomposed microservices architecture, inventory management is owned exclusively
 * by the Catalog Service and exposed via REST API endpoints called by the Order Service's
 * Saga orchestrator during distributed order transactions.
 *
 * <h3>Key Design Decisions (from AAP)</h3>
 * <ul>
 *   <li>{@link #decrementInventory(String, int, String)} includes an {@code orderId} parameter
 *       as an <strong>idempotency key</strong> to support safe Saga retries without
 *       double-decrementing inventory (AAP Section 0.7.1).</li>
 *   <li>{@link #restoreInventory(String, int, String)} is the <strong>compensating action</strong>
 *       for the orchestration-based Saga — it restores inventory when order placement fails
 *       after a successful decrement.</li>
 *   <li>The {@code Inventory} entity uses {@code @Version} optimistic locking for entity-based
 *       operations, while the {@code decrementQuantity} bulk JPQL query uses a
 *       {@code qty >= :decrement} guard to prevent negative inventory.</li>
 * </ul>
 *
 * <h3>Cross-Service Integration</h3>
 * <ul>
 *   <li>{@code decrementInventory} — called by Order Service via
 *       {@code POST /api/items/{id}/inventory/decrement} (through {@code ItemController})</li>
 *   <li>{@code restoreInventory} — called by Order Service via
 *       {@code POST /api/items/{id}/inventory/restore} (Saga compensation, through
 *       {@code ItemController})</li>
 *   <li>{@code getInventoryQuantity} — called by {@code ItemController} for
 *       {@code GET /api/items/{id}/inventory}</li>
 * </ul>
 *
 * @see Inventory
 * @see InventoryRepository
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryRepository inventoryRepository;

    private final InventoryReservationRepository reservationRepository;

    /**
     * Constructs an {@code InventoryService} with the required repository dependencies.
     *
     * <p>Uses constructor injection (no {@code @Autowired} annotation) consistent with
     * Spring's recommended injection pattern and the monolith's service class conventions.
     *
     * @param inventoryRepository    the Spring Data JPA repository for inventory operations
     * @param reservationRepository  the repository for inventory reservation deduplication records
     */
    public InventoryService(InventoryRepository inventoryRepository,
                            InventoryReservationRepository reservationRepository) {
        this.inventoryRepository = inventoryRepository;
        this.reservationRepository = reservationRepository;
    }

    /**
     * Atomically decrements the inventory quantity for the specified item.
     *
     * <p>This is the <strong>critical Saga step</strong> in the distributed order transaction.
     * The Order Service's {@code OrderSagaOrchestrator} calls this method (via
     * {@code POST /api/items/{id}/inventory/decrement}) for each line item in an order.
     *
     * <p><strong>Monolith equivalence:</strong> The net effect is identical to
     * {@code itemMapper.updateInventoryQuantity({itemId, increment})} — both reduce inventory
     * by the specified amount. However, this implementation adds an <strong>improvement</strong>
     * over the monolith: the {@code qty >= :decrement} guard in the repository query prevents
     * inventory from going negative, which the original MyBatis SQL did not enforce.
     *
     * <p><strong>Idempotency (AAP Section 0.7.1):</strong> The {@code orderId} parameter
     * serves as the idempotency key. Before decrementing, this method checks the
     * {@code inventory_reservation} table for an existing reservation with the same
     * (orderId, itemId) combination. If a reservation already exists, the decrement
     * was previously applied and the method returns {@code true} immediately without
     * modifying inventory — guaranteeing exactly-once semantics for each (orderId, itemId)
     * pair even under Saga retries caused by network timeouts.
     *
     * <p>When a decrement succeeds, a new {@link InventoryReservation} record is persisted
     * within the same transaction, creating a durable proof of the decrement. The
     * UNIQUE constraint on (order_id, item_id) provides a database-level safety net
     * against any application-level race conditions.
     *
     * @param itemId  the item identifier whose inventory should be decremented;
     *                must correspond to an existing inventory record
     * @param quantity the number of units to subtract from current stock; must be positive
     * @param orderId the order identifier acting as an idempotency key for Saga retry safety
     * @return {@code true} if the decrement succeeded (or was already applied for this orderId),
     *         {@code false} if the item was not found or had insufficient stock
     */
    @Transactional
    public boolean decrementInventory(String itemId, int quantity, String orderId) {
        log.info("Decrementing inventory for item {} by {} for order {}", itemId, quantity, orderId);

        // Idempotency check: if a reservation already exists for this (orderId, itemId),
        // the decrement was already applied — return success without double-decrementing.
        Optional<InventoryReservation> existingReservation =
                reservationRepository.findByOrderIdAndItemId(orderId, itemId);
        if (existingReservation.isPresent()) {
            log.info("Idempotency guard: reservation already exists for order {} item {} (reservationId={}). "
                    + "Returning success without re-decrementing.",
                    orderId, itemId, existingReservation.orElseThrow().getId());
            return true;
        }

        // No existing reservation — perform the actual inventory decrement.
        int rowsAffected = inventoryRepository.decrementQuantity(itemId, quantity);

        if (rowsAffected == 0) {
            log.warn("Failed to decrement inventory for item {}: insufficient stock or item not found", itemId);
            return false;
        }

        // Persist the reservation record to guard against future retries.
        InventoryReservation reservation = new InventoryReservation(orderId, itemId, quantity);
        reservationRepository.save(reservation);

        log.info("Successfully decremented inventory for item {} by {} for order {} (reservationId={})",
                itemId, quantity, orderId, reservation.getId());
        return true;
    }

    /**
     * Restores inventory quantity for the specified item as a Saga compensating action.
     *
     * <p>This method is the <strong>compensating transaction</strong> for the orchestration-based
     * Saga pattern (AAP Section 0.7.1). When an order fails after inventory has been
     * successfully decremented (e.g., the confirmation write fails or a subsequent line item's
     * decrement fails), this method reverses the decrement by adding the quantity back.
     *
     * <p>Unlike {@link #decrementInventory(String, int, String)}, which uses a bulk JPQL
     * {@code @Modifying @Query} for atomic decrement, this method loads the entity via
     * {@code findById()}, modifies the quantity, and saves it back. This approach
     * <strong>engages the {@code @Version} optimistic locking</strong> on the {@code Inventory}
     * entity, providing concurrent safety for the restore operation.
     *
     * <p>If the inventory record is not found, an {@link IllegalStateException} is thrown.
     * This should never occur in normal operation since a restore is only invoked after a
     * successful decrement, meaning the inventory record must exist.
     *
     * @param itemId  the item identifier whose inventory should be restored
     * @param quantity the number of units to add back to the current stock
     * @param orderId the order identifier for traceability and audit logging
     * @throws IllegalStateException if no inventory record exists for the given item ID
     */
    @Transactional
    public void restoreInventory(String itemId, int quantity, String orderId) {
        log.info("Restoring inventory for item {} by {} for order {} (compensation)", itemId, quantity, orderId);

        Optional<Inventory> inventoryOpt = inventoryRepository.findById(itemId);
        if (inventoryOpt.isPresent()) {
            Inventory inventory = inventoryOpt.orElseThrow();
            int previousQty = inventory.getQty();
            inventory.setQty(previousQty + quantity);
            inventoryRepository.save(inventory);
            log.info("Successfully restored inventory for item {} (id={}) from {} to {} for order {}",
                    itemId, inventory.getItemId(), previousQty, inventory.getQty(), orderId);

            // Remove the reservation record so that a future retry of the same order
            // does not find a stale reservation and skip the decrement.
            reservationRepository.findByOrderIdAndItemId(orderId, itemId)
                    .ifPresent(reservation -> {
                        reservationRepository.delete(reservation);
                        log.info("Removed reservation record for order {} item {} during compensation",
                                orderId, itemId);
                    });
        } else {
            log.error("Cannot restore inventory for item {}: inventory record not found", itemId);
            throw new IllegalStateException("Inventory record not found for item: " + itemId);
        }
    }

    /**
     * Returns the current inventory quantity for the specified item.
     *
     * <p><strong>Monolith equivalence:</strong> This method produces the same result as the
     * monolith's MyBatis query:
     * <pre>
     *   SELECT QTY AS value FROM INVENTORY WHERE ITEMID = #{itemId}
     * </pre>
     * For existing items, it returns the current quantity. For non-existent inventory records,
     * it returns {@code 0} — matching the monolith's behavior where missing inventory
     * effectively means zero stock.
     *
     * <p>This is a <strong>read-only operation</strong> and does not require a
     * {@code @Transactional} annotation. It is called by {@code ItemController} for the
     * {@code GET /api/items/{id}/inventory} endpoint.
     *
     * @param itemId the item identifier to look up
     * @return the current inventory quantity, or {@code 0} if no inventory record exists
     */
    public int getInventoryQuantity(String itemId) {
        return inventoryRepository.findById(itemId)
                .map(Inventory::getQty)
                .orElse(0);
    }

}
