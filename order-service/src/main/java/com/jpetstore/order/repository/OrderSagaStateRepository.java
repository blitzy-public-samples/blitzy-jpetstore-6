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
package com.jpetstore.order.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jpetstore.order.saga.OrderSagaState;

/**
 * Spring Data JPA repository for {@link OrderSagaState} entities persisted in the
 * {@code order_saga_state} table of the Order Service's PostgreSQL database
 * ({@code jpetstore_order}).
 *
 * <p>This repository is central to the orchestration-based Saga pattern's crash
 * recovery mechanism. The {@code OrderSagaOrchestrator} persists an
 * {@code OrderSagaState} at each step of the distributed order transaction
 * (CREATE_ORDER → RESERVE_INVENTORY → CONFIRM_ORDER). If the JVM crashes
 * between steps, the reconciliation job queries this repository for sagas in
 * non-terminal states and resumes or compensates them accordingly.</p>
 *
 * <h3>Inherited CRUD Operations (from {@link JpaRepository})</h3>
 * <ul>
 *   <li>{@code save(OrderSagaState)} — persist or update a saga state record</li>
 *   <li>{@code findById(String sagaId)} — find by UUID primary key</li>
 *   <li>{@code findAll()} — list all saga state records</li>
 *   <li>{@code deleteById(String sagaId)} — delete a specific saga record</li>
 *   <li>{@code count()} — count total saga state records</li>
 * </ul>
 *
 * <h3>Custom Query Methods</h3>
 * <ul>
 *   <li>{@link #findByOrderId(int)} — retrieve all saga states for a given order</li>
 *   <li>{@link #findByStatus(String)} — retrieve all sagas in a specific status
 *       (e.g., {@code "PENDING"}, {@code "INVENTORY_RESERVED"}) for reconciliation</li>
 *   <li>{@link #findByOrderIdAndStatusNot(int, String)} — find the active
 *       (non-terminal) saga for an order, used to prevent duplicate saga creation</li>
 * </ul>
 *
 * <h3>Reconciliation Usage Pattern</h3>
 * <pre>{@code
 * // Find all sagas stuck in non-terminal states for reconciliation:
 * List<OrderSagaState> pendingSagas = repository.findByStatus("PENDING");
 * List<OrderSagaState> reservedSagas = repository.findByStatus("INVENTORY_RESERVED");
 * List<OrderSagaState> compensatingSagas = repository.findByStatus("COMPENSATING");
 *
 * // For each stalled saga, check createdAt/updatedAt against timeout threshold
 * // and either complete or compensate.
 * }</pre>
 *
 * <h3>Key Design Decisions</h3>
 * <ul>
 *   <li>Primary key type is {@code String} (UUID) — globally unique without
 *       requiring database sequence coordination.</li>
 *   <li>All custom query methods use Spring Data JPA derived query naming
 *       conventions — no {@code @Query} annotations needed.</li>
 *   <li>The repository contains zero business logic — all Saga orchestration,
 *       state transitions, and compensation logic reside in
 *       {@code OrderSagaOrchestrator} and {@code InventoryCompensation}.</li>
 * </ul>
 *
 * @see OrderSagaState
 * @see com.jpetstore.order.service.OrderSagaOrchestrator
 * @see com.jpetstore.order.saga.InventoryCompensation
 */
@Repository
public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, String> {

    /**
     * Finds all saga state records associated with a specific order.
     *
     * <p>An order may have multiple saga records if previous attempts failed
     * and new sagas were created for retry. Typically, only one will be in a
     * non-terminal state at any time.</p>
     *
     * <p>Spring Data JPA derives: {@code SELECT * FROM order_saga_state WHERE order_id = ?}</p>
     *
     * @param orderId the order ID to search for
     * @return a list of all saga state records for the given order; empty list if none found
     */
    List<OrderSagaState> findByOrderId(int orderId);

    /**
     * Finds all saga state records with a specific status.
     *
     * <p>This method is primarily used by the reconciliation job to discover
     * sagas that may be stalled in non-terminal states. For example:</p>
     * <ul>
     *   <li>{@code findByStatus("PENDING")} — sagas waiting for inventory reservation</li>
     *   <li>{@code findByStatus("INVENTORY_RESERVED")} — sagas awaiting confirmation</li>
     *   <li>{@code findByStatus("COMPENSATING")} — sagas mid-compensation</li>
     * </ul>
     *
     * <p>Spring Data JPA derives: {@code SELECT * FROM order_saga_state WHERE status = ?}</p>
     *
     * @param status the saga status to filter by (e.g., {@code "PENDING"},
     *               {@code "INVENTORY_RESERVED"}, {@code "COMPLETED"},
     *               {@code "COMPENSATING"}, {@code "FAILED"})
     * @return a list of all saga state records with the given status; empty list if none found
     */
    List<OrderSagaState> findByStatus(String status);

    /**
     * Finds the active (non-terminal) saga for a specific order by excluding
     * sagas with a given status.
     *
     * <p>This method is used to check whether an active saga already exists
     * for an order before creating a new one, preventing duplicate saga
     * creation. Typical usage:</p>
     * <pre>{@code
     * // Check if there's an active saga (not yet FAILED) for this order:
     * Optional<OrderSagaState> activeSaga =
     *     repository.findByOrderIdAndStatusNot(orderId, "FAILED");
     *
     * // Or check if there's a non-completed saga:
     * Optional<OrderSagaState> incompleteSaga =
     *     repository.findByOrderIdAndStatusNot(orderId, "COMPLETED");
     * }</pre>
     *
     * <p>Spring Data JPA derives:
     * {@code SELECT * FROM order_saga_state WHERE order_id = ? AND status != ?}</p>
     *
     * @param orderId the order ID to search for
     * @param status  the status to exclude from the search
     * @return an {@link Optional} containing the matching saga state, or empty
     *         if no saga exists for the order with a status other than the excluded one
     */
    Optional<OrderSagaState> findByOrderIdAndStatusNot(int orderId, String status);
}
