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
package com.jpetstore.order.saga;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * JPA entity representing the persisted state of an orchestration-based Saga
 * for the distributed order transaction.
 *
 * <p>This entity is central to the Order Service's crash recovery mechanism.
 * When the Order Service restarts after a failure, it can query for Saga instances
 * in non-terminal states (PENDING, INVENTORY_RESERVED, COMPENSATING) and resume
 * or compensate them accordingly. Without this persistence, a crash between
 * the order write and the inventory reservation confirmation would leave the
 * system in an inconsistent state.</p>
 *
 * <h3>Saga State Machine</h3>
 * <p>The Saga progresses through the following states:</p>
 * <pre>{@code
 *   PENDING ──(inventory reserved)──> INVENTORY_RESERVED ──(confirmed)──> COMPLETED
 *      │                                       │
 *      │ (creation failed)                     │ (confirmation failed)
 *      v                                       v
 *   FAILED                              COMPENSATING ──(compensated)──> FAILED
 * }</pre>
 *
 * <ul>
 *   <li>{@code PENDING} — Initial state after the order record is written locally
 *       (OrderSagaStep.CREATE_ORDER completed). The Saga is waiting to reserve
 *       inventory via the Catalog Service.</li>
 *   <li>{@code INVENTORY_RESERVED} — Inventory has been successfully decremented
 *       for all line items via the Catalog Service REST API. The Saga is waiting
 *       for the final order confirmation step.</li>
 *   <li>{@code COMPLETED} — Terminal success state. The order is confirmed and all
 *       inventory reservations are committed. No further action needed.</li>
 *   <li>{@code COMPENSATING} — A failure occurred after partial progress. The Saga
 *       is actively restoring already-decremented inventory via
 *       {@code InventoryCompensation.compensate()}.</li>
 *   <li>{@code FAILED} — Terminal failure state. Either the order was never created,
 *       or compensation completed successfully after a mid-saga failure.</li>
 * </ul>
 *
 * <h3>Reconciliation Job</h3>
 * <p>A scheduled reconciliation job queries for Saga instances that have been
 * in {@code PENDING} or {@code INVENTORY_RESERVED} state beyond a configurable
 * timeout threshold (e.g., 60 seconds). These represent Sagas that may have
 * stalled due to service crashes or network partitions. The job either completes
 * or compensates them based on the current state of the external systems.</p>
 *
 * <h3>Persistence Details</h3>
 * <p>This entity is stored in the Order Service's PostgreSQL database
 * ({@code jpetstore_order}). The table is created by Liquibase (or Hibernate
 * auto-DDL in development). The UUID primary key ensures global uniqueness
 * without sequence coordination.</p>
 *
 * @see OrderSagaStep
 * @see com.jpetstore.order.service.OrderSagaOrchestrator
 */
@Entity
@Table(name = "order_saga_state")
public class OrderSagaState implements Serializable {

    private static final long serialVersionUID = 1L;

    // -----------------------------------------------------------------------
    // Primary Key — UUID for global uniqueness
    // -----------------------------------------------------------------------

    /**
     * Unique identifier for this Saga instance.
     *
     * <p>Uses UUID (v4, random) for globally unique identification without
     * requiring a database sequence. This allows Saga IDs to be generated
     * in application code before any database interaction, which is useful
     * for logging and correlation from the very start of the Saga.</p>
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "saga_id", nullable = false, updatable = false)
    private UUID sagaId;

    // -----------------------------------------------------------------------
    // Order Reference
    // -----------------------------------------------------------------------

    /**
     * The order ID that this Saga is orchestrating.
     *
     * <p>References the {@code orders.order_id} column within the same database.
     * Each order has at most one active Saga. The Saga orchestrator uses this
     * field to correlate Saga state with the order record and its line items.</p>
     */
    @Column(name = "order_id", nullable = false)
    private int orderId;

    // -----------------------------------------------------------------------
    // Saga Progress Tracking
    // -----------------------------------------------------------------------

    /**
     * The current step in the Saga execution sequence.
     *
     * <p>Persisted as the enum constant name (String) via
     * {@code @Enumerated(EnumType.STRING)} so that the database value is
     * human-readable and resilient to enum ordinal changes. Values:
     * {@code CREATE_ORDER}, {@code RESERVE_INVENTORY}, {@code CONFIRM_ORDER}.</p>
     *
     * @see OrderSagaStep
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 30)
    private OrderSagaStep currentStep;

    /**
     * The current status of the Saga state machine.
     *
     * <p>Persisted as a String enum name. Terminal states: {@code COMPLETED},
     * {@code FAILED}. Non-terminal states: {@code PENDING},
     * {@code INVENTORY_RESERVED}, {@code COMPENSATING}.</p>
     *
     * @see SagaStatus
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private SagaStatus status;

    // -----------------------------------------------------------------------
    // Lifecycle Timestamps
    // -----------------------------------------------------------------------

    /**
     * Timestamp when this Saga instance was created.
     *
     * <p>Set automatically by the {@link #onCreate()} lifecycle callback.
     * Used by the reconciliation job to identify stalled Sagas that have
     * been in a non-terminal state beyond the configured timeout threshold.</p>
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of the most recent state transition.
     *
     * <p>Updated automatically by the {@link #onUpdate()} lifecycle callback
     * on every persist or merge operation. Provides an audit trail and helps
     * the reconciliation job distinguish between recently-active and truly-stalled
     * Sagas.</p>
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // -----------------------------------------------------------------------
    // JPA Lifecycle Callbacks
    // -----------------------------------------------------------------------

    /**
     * Sets {@code createdAt} and {@code updatedAt} to the current time
     * before the entity is first persisted.
     */
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Updates {@code updatedAt} to the current time before the entity is merged.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-arg constructor required by the JPA specification for entity instantiation
     * by the persistence provider.
     */
    public OrderSagaState() {
        // JPA requires a no-arg constructor
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the unique Saga identifier.
     *
     * @return the saga ID as {@link UUID}
     */
    public UUID getSagaId() {
        return sagaId;
    }

    /**
     * Sets the unique Saga identifier.
     *
     * @param sagaId the saga ID to set
     */
    public void setSagaId(UUID sagaId) {
        this.sagaId = sagaId;
    }

    /**
     * Returns the order ID that this Saga is orchestrating.
     *
     * @return the order ID
     */
    public int getOrderId() {
        return orderId;
    }

    /**
     * Sets the order ID that this Saga is orchestrating.
     *
     * @param orderId the order ID to set
     */
    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    /**
     * Returns the current step in the Saga execution sequence.
     *
     * @return the current {@link OrderSagaStep}
     */
    public OrderSagaStep getCurrentStep() {
        return currentStep;
    }

    /**
     * Sets the current step in the Saga execution sequence.
     *
     * @param currentStep the step to set
     */
    public void setCurrentStep(OrderSagaStep currentStep) {
        this.currentStep = currentStep;
    }

    /**
     * Returns the current Saga status.
     *
     * @return the current {@link SagaStatus}
     */
    public SagaStatus getStatus() {
        return status;
    }

    /**
     * Sets the current Saga status.
     *
     * @param status the status to set
     */
    public void setStatus(SagaStatus status) {
        this.status = status;
    }

    /**
     * Returns the timestamp when this Saga was created.
     *
     * @return the creation timestamp
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the creation timestamp to set
     */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Returns the timestamp of the most recent state transition.
     *
     * @return the last-updated timestamp
     */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Sets the last-updated timestamp.
     *
     * @param updatedAt the last-updated timestamp to set
     */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // -----------------------------------------------------------------------
    // equals, hashCode, toString
    // -----------------------------------------------------------------------

    /**
     * Two Saga states are equal if they have the same {@code sagaId}.
     *
     * @param o the object to compare with
     * @return {@code true} if the objects represent the same Saga instance
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        OrderSagaState that = (OrderSagaState) o;
        return Objects.equals(sagaId, that.sagaId);
    }

    /**
     * Hash code based on {@code sagaId}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(sagaId);
    }

    /**
     * Returns a string representation of this Saga state for debugging and logging.
     *
     * @return a string containing the Saga's key fields
     */
    @Override
    public String toString() {
        return "OrderSagaState{"
                + "sagaId=" + sagaId
                + ", orderId=" + orderId
                + ", currentStep=" + currentStep
                + ", status=" + status
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + '}';
    }

    // -----------------------------------------------------------------------
    // Saga Status Enum (nested)
    // -----------------------------------------------------------------------

    /**
     * Enumeration of all possible Saga status values.
     *
     * <p>Persisted as String via {@code @Enumerated(EnumType.STRING)}. The constant
     * names must remain stable and must not be renamed without a corresponding
     * database migration, as they are stored directly in the {@code status} column.</p>
     */
    public enum SagaStatus {

        /**
         * Initial state — order created locally, waiting for inventory reservation.
         */
        PENDING,

        /**
         * Inventory successfully reserved via Catalog Service — waiting for
         * final order confirmation.
         */
        INVENTORY_RESERVED,

        /**
         * Terminal success state — order confirmed, all inventory committed.
         */
        COMPLETED,

        /**
         * Active compensation in progress — restoring previously decremented
         * inventory after a mid-saga failure.
         */
        COMPENSATING,

        /**
         * Terminal failure state — order not placed, or compensation completed
         * after a failure.
         */
        FAILED
    }
}
