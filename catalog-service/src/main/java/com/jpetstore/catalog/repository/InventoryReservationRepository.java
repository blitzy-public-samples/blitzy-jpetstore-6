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
package com.jpetstore.catalog.repository;

import com.jpetstore.catalog.entity.InventoryReservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for the {@link InventoryReservation} entity.
 *
 * <p>This repository supports the idempotency mechanism described in
 * AAP Section 0.7.1: <em>"Every inventory reservation request includes the
 * orderId as an idempotency key. Catalog Service stores reservation records
 * indexed by orderId and returns success for duplicate requests without
 * double-decrementing."</em></p>
 *
 * <h3>Key Operations</h3>
 * <ul>
 *   <li>{@link #findByOrderIdAndItemId(String, String)} — Check for existing
 *       reservation before decrementing (idempotency guard)</li>
 *   <li>{@link #findByOrderId(String)} — Retrieve all reservations for an order
 *       (used during Saga compensation to identify items that need restoring)</li>
 *   <li>{@link #deleteByOrderId(String)} — Clean up reservations after successful
 *       compensation or order confirmation</li>
 * </ul>
 *
 * <p>No {@code @Repository} annotation is needed — Spring Boot auto-detects
 * interfaces extending {@link JpaRepository} during component scanning.</p>
 *
 * @see InventoryReservation
 * @see com.jpetstore.catalog.service.InventoryService
 */
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, Long> {

    /**
     * Finds a reservation by order ID and item ID.
     *
     * <p>This is the primary idempotency check: if a reservation already exists
     * for the given (orderId, itemId) pair, the decrement was already applied
     * and should not be repeated.</p>
     *
     * @param orderId the order ID (idempotency key)
     * @param itemId  the item ID
     * @return the existing reservation, or empty if no reservation exists
     */
    Optional<InventoryReservation> findByOrderIdAndItemId(String orderId, String itemId);

    /**
     * Finds all reservations associated with a specific order.
     *
     * <p>Used during Saga compensation: when an order fails, the compensating
     * transaction needs to know which items had their inventory decremented
     * so it can restore the correct quantities.</p>
     *
     * @param orderId the order ID
     * @return list of reservations for the order (may be empty)
     */
    List<InventoryReservation> findByOrderId(String orderId);

    /**
     * Deletes all reservations for a specific order.
     *
     * <p>Called after successful Saga compensation to clean up reservation
     * records, or after order confirmation when the reservation data is no
     * longer needed for idempotency (the order is finalized).</p>
     *
     * @param orderId the order ID
     */
    void deleteByOrderId(String orderId);
}
