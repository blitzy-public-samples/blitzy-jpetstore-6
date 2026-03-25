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
package com.jpetstore.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * JPA entity for the {@code inventory_reservation} table in the Catalog Service's
 * PostgreSQL database.
 *
 * <p>This entity provides <strong>idempotency</strong> for inventory decrement
 * operations invoked by the Order Service's Saga orchestrator during order
 * placement. Each reservation record represents a single inventory decrement
 * that was successfully applied for a specific (orderId, itemId) combination.
 *
 * <h3>Design Rationale (AAP Section 0.7.1)</h3>
 * <p>The AAP requires: <em>"Every inventory reservation request includes the
 * orderId as an idempotency key. Catalog Service stores reservation records
 * indexed by orderId and returns success for duplicate requests without
 * double-decrementing."</em></p>
 *
 * <p>Without this table, a network timeout during the Saga's inventory
 * reservation step could cause the Order Service to retry the decrement
 * request, resulting in double-decrement (overselling). With this table,
 * the {@code InventoryService} checks for an existing reservation before
 * decrementing, and returns success immediately if a matching reservation
 * exists — guaranteeing exactly-once semantics for each (orderId, itemId)
 * pair.</p>
 *
 * <h3>Table Schema</h3>
 * <pre>
 * CREATE TABLE inventory_reservation (
 *     id          BIGSERIAL    PRIMARY KEY,
 *     order_id    VARCHAR(36)  NOT NULL,
 *     item_id     VARCHAR(10)  NOT NULL,
 *     quantity    INTEGER      NOT NULL,
 *     created_at  TIMESTAMP    NOT NULL,
 *     UNIQUE (order_id, item_id)
 * );
 * </pre>
 *
 * <h3>Uniqueness Constraint</h3>
 * <p>The composite unique constraint on (order_id, item_id) enforces
 * idempotency at the database level. Even if the application-level check
 * in {@code InventoryService} races with a concurrent retry, the DB
 * constraint guarantees that at most one reservation can exist per
 * (orderId, itemId) pair.</p>
 *
 * @see com.jpetstore.catalog.service.InventoryService#decrementInventory(String, int, String)
 * @see com.jpetstore.catalog.repository.InventoryReservationRepository
 */
@Entity
@Table(name = "inventory_reservation", uniqueConstraints = {
        @UniqueConstraint(name = "uq_reservation_order_item",
                columnNames = {"order_id", "item_id"})
})
public class InventoryReservation implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Auto-generated surrogate primary key.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * The order ID that triggered this inventory reservation.
     *
     * <p>Used as the idempotency key together with {@link #itemId}. The Order
     * Service sends this value with every inventory decrement request so that
     * retries are safely deduplicated.</p>
     *
     * <p>Stored as {@code varchar(36)} to accommodate both integer order IDs
     * and UUID-based order IDs used by the Saga orchestrator.</p>
     */
    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    /**
     * The item ID whose inventory was decremented.
     *
     * <p>References the {@code inventory.itemid} column within the same
     * database, but is NOT a JPA FK relationship for flexibility.</p>
     */
    @Column(name = "item_id", nullable = false, length = 10)
    private String itemId;

    /**
     * The quantity that was decremented from inventory for this reservation.
     *
     * <p>Stored for audit purposes and to support compensation — when the
     * Order Service's {@code InventoryCompensation} restores inventory
     * after a failed Saga, it can verify the restore quantity matches
     * the original decrement.</p>
     */
    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * Timestamp when this reservation was created.
     *
     * <p>Provides an audit trail and can be used to identify stale
     * reservations that were never confirmed or compensated.</p>
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default no-arg constructor required by JPA.
     */
    public InventoryReservation() {
        // JPA requires a no-arg constructor
    }

    /**
     * Convenience constructor for creating a new reservation.
     *
     * @param orderId  the order ID (idempotency key)
     * @param itemId   the item ID whose inventory is being decremented
     * @param quantity the quantity to decrement
     */
    public InventoryReservation(String orderId, String itemId, int quantity) {
        this.orderId = orderId;
        this.itemId = itemId;
        this.quantity = quantity;
        this.createdAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "InventoryReservation{" +
                "id=" + id +
                ", orderId='" + orderId + '\'' +
                ", itemId='" + itemId + '\'' +
                ", quantity=" + quantity +
                ", createdAt=" + createdAt +
                '}';
    }
}
