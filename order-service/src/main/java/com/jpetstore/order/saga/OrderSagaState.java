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
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * in non-terminal states ({@code PENDING}, {@code INVENTORY_RESERVED},
 * {@code COMPENSATING}) and resume or compensate them accordingly. Without this
 * persistence, a crash between the order write and the inventory reservation
 * confirmation would leave the system in an inconsistent state.</p>
 *
 * <h3>Saga State Machine (per AAP Section 0.7.1)</h3>
 * <p>The Saga progresses through the following states:</p>
 * <pre>{@code
 *   PENDING ──(inventory reserved)──> INVENTORY_RESERVED ──(confirmed)──> COMPLETED
 *      │                                       │
 *      │ (creation failed /                    │ (confirmation failed)
 *      │  insufficient stock)                  v
 *      v                                COMPENSATING ──(compensated)──> FAILED
 *   FAILED
 * }</pre>
 *
 * <h3>Status Values</h3>
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
 * in a non-terminal state beyond a configurable timeout threshold (e.g., 60 seconds).
 * The query pattern:</p>
 * <pre>{@code
 * SELECT * FROM order_saga_state
 *   WHERE status NOT IN ('COMPLETED', 'FAILED')
 *     AND created_at < NOW() - INTERVAL '60 seconds'
 * }</pre>
 * <p>These represent Sagas that may have stalled due to service crashes or network
 * partitions. The job either completes or compensates them based on the current state
 * of the external systems.</p>
 *
 * <h3>Key Invariants (from AAP Section 0.7.1)</h3>
 * <ul>
 *   <li>A confirmed order (status={@code COMPLETED}) always has a corresponding
 *       inventory decrement.</li>
 *   <li>A failed or rolled-back order (status={@code FAILED}) never decrements
 *       inventory (or compensation has fully restored it).</li>
 *   <li>The {@code PENDING} → {@code COMPLETED}/{@code FAILED} state machine is
 *       the single source of truth for distributed transaction outcome.</li>
 * </ul>
 *
 * <h3>Persistence Details</h3>
 * <p>This entity is stored in the Order Service's PostgreSQL database
 * ({@code jpetstore_order}) in the {@code order_saga_state} table. The table is
 * created by Liquibase ({@code 001-initial-schema.xml}) alongside {@code orders},
 * {@code orderstatus}, and {@code lineitem}. The UUID primary key ensures global
 * uniqueness without sequence coordination.</p>
 *
 * <h3>Design Note — Why This Entity Exists</h3>
 * <p>In the monolith, {@code OrderService.insertOrder()} runs as a single
 * {@code @Transactional} ACID transaction. If the JVM crashes mid-transaction,
 * the database automatically rolls back. After decomposition, the inventory
 * decrement is a REST call to the Catalog Service — a different database and
 * process. This entity provides the durable state tracking needed for crash
 * recovery and compensation in the distributed saga.</p>
 *
 * @see OrderSagaStep
 * @see com.jpetstore.order.saga.OrderSagaOrchestrator
 */
@Entity
@Table(name = "order_saga_state")
public class OrderSagaState implements Serializable {

    private static final long serialVersionUID = 1L;

    // -----------------------------------------------------------------------
    // Status Constants — valid values for the status field
    // -----------------------------------------------------------------------

    /** Initial state: order created locally, waiting for inventory reservation. */
    public static final String STATUS_PENDING = "PENDING";

    /** Inventory successfully reserved via Catalog Service, awaiting confirmation. */
    public static final String STATUS_INVENTORY_RESERVED = "INVENTORY_RESERVED";

    /** Terminal success: order confirmed, all inventory committed. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** Active compensation in progress: restoring decremented inventory. */
    public static final String STATUS_COMPENSATING = "COMPENSATING";

    /** Terminal failure: order not placed, or compensation completed. */
    public static final String STATUS_FAILED = "FAILED";

    // -----------------------------------------------------------------------
    // Primary Key — UUID (stored as String for simplicity and portability)
    // -----------------------------------------------------------------------

    /**
     * Unique identifier for this Saga instance.
     *
     * <p>Uses UUID v4 (random) stored as a 36-character String for globally
     * unique identification without requiring a database sequence. Generated
     * automatically in the {@link #onCreate()} lifecycle callback if not set
     * by the service layer. This allows Saga IDs to be generated in application
     * code before any database interaction, which is useful for logging and
     * correlation from the very start of the Saga.</p>
     */
    @Id
    @Column(name = "saga_id", nullable = false, updatable = false, length = 36)
    private String sagaId;

    // -----------------------------------------------------------------------
    // Order Reference
    // -----------------------------------------------------------------------

    /**
     * The order ID that this Saga is orchestrating.
     *
     * <p>References the {@code orders.order_id} column within the same database.
     * Each order has at most one active Saga. The Saga orchestrator uses this
     * field to correlate Saga state with the order record and its line items.</p>
     *
     * <p>NOT a JPA relationship ({@code @ManyToOne}) — the saga state is a
     * separate concern from the order entity, though both reside in the same
     * service/database.</p>
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
     * <p>Stored as a plain String for flexibility and forward-compatibility.
     * Valid values: {@code "PENDING"}, {@code "INVENTORY_RESERVED"},
     * {@code "COMPLETED"}, {@code "COMPENSATING"}, {@code "FAILED"}.</p>
     *
     * <p>Terminal states: {@code COMPLETED} and {@code FAILED}.<br>
     * Non-terminal states: {@code PENDING}, {@code INVENTORY_RESERVED},
     * {@code COMPENSATING}.</p>
     *
     * @see #STATUS_PENDING
     * @see #STATUS_INVENTORY_RESERVED
     * @see #STATUS_COMPLETED
     * @see #STATUS_COMPENSATING
     * @see #STATUS_FAILED
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // -----------------------------------------------------------------------
    // Lifecycle Timestamps
    // -----------------------------------------------------------------------

    /**
     * Timestamp when this Saga instance was created.
     *
     * <p>Set automatically by the {@link #onCreate()} lifecycle callback.
     * Used by the reconciliation job to identify stalled Sagas that have
     * been in a non-terminal state beyond the configured timeout threshold
     * (e.g., 60 seconds per AAP Section 0.7.1).</p>
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Timestamp of the most recent state transition.
     *
     * <p>Updated automatically by the {@link #onUpdate()} lifecycle callback
     * on every merge operation, and also initialized during persist. Provides
     * an audit trail and helps the reconciliation job distinguish between
     * recently-active and truly-stalled Sagas.</p>
     */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // -----------------------------------------------------------------------
    // JPA Lifecycle Callbacks
    // -----------------------------------------------------------------------

    /**
     * JPA lifecycle callback invoked before this entity is first persisted.
     *
     * <p>Generates a UUID for {@code sagaId} if it has not been set by the
     * service layer, and initializes both {@code createdAt} and {@code updatedAt}
     * timestamps to the current time.</p>
     */
    @PrePersist
    protected void onCreate() {
        if (this.sagaId == null) {
            this.sagaId = UUID.randomUUID().toString();
        }
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * JPA lifecycle callback invoked before this entity is merged (updated).
     *
     * <p>Updates the {@code updatedAt} timestamp to the current time to track
     * when the last state transition occurred.</p>
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * No-arg constructor required by the JPA specification for entity
     * instantiation by the persistence provider.
     */
    public OrderSagaState() {
        // JPA requires a no-arg constructor
    }

    /**
     * Convenience constructor for creating a new Saga state with the three
     * essential fields. The {@code sagaId}, {@code createdAt}, and
     * {@code updatedAt} fields will be auto-generated by the
     * {@link #onCreate()} lifecycle callback during persist.
     *
     * @param orderId     the order ID that this Saga orchestrates
     * @param currentStep the initial step in the Saga sequence
     * @param status      the initial status (typically {@link #STATUS_PENDING})
     */
    public OrderSagaState(int orderId, OrderSagaStep currentStep, String status) {
        this.orderId = orderId;
        this.currentStep = currentStep;
        this.status = status;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters
    // -----------------------------------------------------------------------

    /**
     * Returns the unique Saga identifier.
     *
     * @return the saga ID as a 36-character UUID string
     */
    public String getSagaId() {
        return sagaId;
    }

    /**
     * Sets the unique Saga identifier.
     *
     * <p>Typically not called directly — the {@link #onCreate()} lifecycle
     * callback generates a UUID automatically. Use this method only if a
     * specific saga ID must be set (e.g., for idempotency or testing).</p>
     *
     * @param sagaId the saga ID to set (expected: 36-character UUID string)
     */
    public void setSagaId(String sagaId) {
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
     * @return the status string (one of {@link #STATUS_PENDING},
     *         {@link #STATUS_INVENTORY_RESERVED}, {@link #STATUS_COMPLETED},
     *         {@link #STATUS_COMPENSATING}, {@link #STATUS_FAILED})
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the current Saga status.
     *
     * <p>Valid values: {@code "PENDING"}, {@code "INVENTORY_RESERVED"},
     * {@code "COMPLETED"}, {@code "COMPENSATING"}, {@code "FAILED"}.
     * Use the {@code STATUS_*} constants defined on this class.</p>
     *
     * @param status the status string to set
     */
    public void setStatus(String status) {
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
     * <p>Typically not called directly — the {@link #onCreate()} lifecycle
     * callback sets this automatically on first persist.</p>
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
     * <p>Typically not called directly — the {@link #onUpdate()} lifecycle
     * callback sets this automatically on every merge.</p>
     *
     * @param updatedAt the last-updated timestamp to set
     */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    // -----------------------------------------------------------------------
    // toString
    // -----------------------------------------------------------------------

    /**
     * Returns a string representation of this Saga state for operational
     * logging and debugging.
     *
     * <p>Includes all key fields to facilitate troubleshooting of stuck or
     * failed sagas in production logs.</p>
     *
     * @return a string containing the Saga's key fields
     */
    @Override
    public String toString() {
        return "OrderSagaState{"
                + "sagaId='" + sagaId + '\''
                + ", orderId=" + orderId
                + ", currentStep=" + currentStep
                + ", status='" + status + '\''
                + ", createdAt=" + createdAt
                + ", updatedAt=" + updatedAt
                + '}';
    }
}
