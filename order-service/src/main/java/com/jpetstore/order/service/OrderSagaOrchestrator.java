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
package com.jpetstore.order.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;
import com.jpetstore.order.entity.OrderStatus;
import com.jpetstore.order.repository.LineItemRepository;
import com.jpetstore.order.repository.OrderRepository;
import com.jpetstore.order.repository.OrderSagaStateRepository;
import com.jpetstore.order.repository.OrderStatusRepository;
import com.jpetstore.order.saga.InventoryCompensation;
import com.jpetstore.order.saga.OrderSagaState;
import com.jpetstore.order.saga.OrderSagaStep;

/**
 * Saga orchestrator for the distributed order transaction.
 *
 * <p>This is the <strong>highest-risk</strong> component in the JPetStore
 * monolith-to-microservices decomposition. It replaces the monolith's single
 * {@code @Transactional OrderService.insertOrder()} method — which performed
 * 2N+4 operations across two bounded contexts (Order and Catalog) in a single
 * ACID transaction — with a three-step orchestration-based Saga pattern.</p>
 *
 * <h3>Step Ordering — REVERSED from Monolith</h3>
 * <ul>
 *   <li><strong>Monolith</strong>: inventory decrement FIRST (lines 62-69),
 *       then order write (lines 71-76)</li>
 *   <li><strong>Saga</strong>: order write FIRST ({@code PENDING} state), then
 *       inventory decrement via REST</li>
 * </ul>
 * <p>Justification: the order record is written first so that there is always a
 * durable record of the attempt. This prevents "phantom decrements" where
 * inventory is reserved but no order record exists.</p>
 *
 * <h3>Three Saga Steps</h3>
 * <ol>
 *   <li><strong>CREATE_ORDER</strong> (local DB write) — Save Order with
 *       status {@code "PENDING"} (PostgreSQL auto-generates orderId via
 *       {@code order_id_seq}), save OrderStatus record replicating the monolith
 *       quirk ({@code lineNum = orderId}), save all LineItems via cascade, and
 *       persist {@link OrderSagaState} for crash recovery.</li>
 *   <li><strong>RESERVE_INVENTORY</strong> (cross-service REST calls) — For each
 *       line item, call {@link CatalogServiceClient#decrementInventory(String, int, String)}
 *       with the {@code orderId} as idempotency key. Track decremented items for
 *       potential compensation. On failure, invoke
 *       {@link InventoryCompensation#compensate(Order, List)} and mark order
 *       {@code "FAILED"}.</li>
 *   <li><strong>CONFIRM_ORDER</strong> (local DB update) — Update Order status to
 *       {@code "CONFIRMED"} and saga state to {@code "COMPLETED"}. This is the
 *       point of no return.</li>
 * </ol>
 *
 * <h3>Failure Scenarios</h3>
 * <ul>
 *   <li><strong>Step 1 fails</strong>: local DB transaction rolls back automatically;
 *       no inventory was decremented — no compensation needed.</li>
 *   <li><strong>Step 2 fails (HTTP 409 — insufficient stock)</strong>: already-decremented
 *       items are compensated via {@link InventoryCompensation}; order status set to
 *       {@code "FAILED"}; method returns the failed order.</li>
 *   <li><strong>Step 2 fails (HTTP 5xx — Catalog unavailable)</strong>:
 *       {@link CatalogServiceClient} throws {@link RuntimeException}; caught locally,
 *       already-decremented items are compensated; order status set to
 *       {@code "FAILED"}.</li>
 *   <li><strong>JVM crash between Steps 2 and 3</strong>: order remains
 *       {@code "PENDING"}; saga state persisted with
 *       {@code "INVENTORY_RESERVED"}; reconciliation job picks up and completes
 *       or compensates.</li>
 * </ul>
 *
 * <h3>Idempotency</h3>
 * <p>Every {@code decrementInventory()} call includes the {@code orderId} as an
 * idempotency key. The Catalog Service stores reservation records indexed by
 * {@code orderId} and returns success for duplicate requests without
 * double-decrementing. This makes retries safe after network timeouts.</p>
 *
 * @see OrderSagaState
 * @see OrderSagaStep
 * @see InventoryCompensation
 * @see CatalogServiceClient
 */
@Service
public class OrderSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

    private final OrderRepository orderRepository;
    private final OrderStatusRepository orderStatusRepository;
    private final LineItemRepository lineItemRepository;
    private final CatalogServiceClient catalogServiceClient;
    private final InventoryCompensation inventoryCompensation;
    private final OrderSagaStateRepository sagaStateRepository;

    /**
     * Constructs the saga orchestrator with all required dependencies injected
     * by Spring's constructor-based dependency injection.
     *
     * @param orderRepository       Spring Data JPA repository for Order entities;
     *                              used in Steps 1 and 3 to persist and update orders
     * @param orderStatusRepository Spring Data JPA repository for OrderStatus entities;
     *                              used in Step 1 to create the initial status record
     * @param lineItemRepository    Spring Data JPA repository for LineItem entities;
     *                              used in Step 1 to explicitly save line items
     *                              (supplements cascade for traceability)
     * @param catalogServiceClient  REST client for cross-service communication with
     *                              the Catalog Service; used in Step 2 for inventory
     *                              decrement
     * @param inventoryCompensation compensating transaction handler; used when Step 2
     *                              fails to restore already-decremented inventory
     * @param sagaStateRepository   Spring Data JPA repository for OrderSagaState
     *                              entities; used at each step to persist saga state
     *                              for crash recovery
     */
    public OrderSagaOrchestrator(
            OrderRepository orderRepository,
            OrderStatusRepository orderStatusRepository,
            LineItemRepository lineItemRepository,
            CatalogServiceClient catalogServiceClient,
            InventoryCompensation inventoryCompensation,
            OrderSagaStateRepository sagaStateRepository) {
        this.orderRepository = orderRepository;
        this.orderStatusRepository = orderStatusRepository;
        this.lineItemRepository = lineItemRepository;
        this.catalogServiceClient = catalogServiceClient;
        this.inventoryCompensation = inventoryCompensation;
        this.sagaStateRepository = sagaStateRepository;
    }

    /**
     * Executes the three-step order saga, transforming the monolith's single
     * {@code @Transactional OrderService.insertOrder()} into a distributed
     * transaction with compensation.
     *
     * <p>The method always returns the Order — on success with status
     * {@code "CONFIRMED"}, on failure with status {@code "FAILED"}. The caller
     * should inspect {@link Order#getStatus()} to determine the outcome.</p>
     *
     * <p>The {@code @Transactional} annotation ensures that all local database
     * writes (Steps 1 and 3) execute within a single database transaction. The
     * cross-service REST calls in Step 2 operate outside the database transaction
     * boundary — they are HTTP calls, not database operations.</p>
     *
     * @param order the Order entity to process; must have lineItems populated
     *              but orderId will be auto-generated by the PostgreSQL sequence
     * @return the processed Order with status {@code "CONFIRMED"} on success or
     *         {@code "FAILED"} on inventory reservation failure
     */
    @Transactional
    public Order executeOrderSaga(Order order) {
        log.info("Starting order saga for user: {}, current order status: {}",
                order.getUsername(), order.getStatus());

        // === STEP 1: CREATE_ORDER (local database write) ===
        Order savedOrder = executeStepCreateOrder(order);

        // === STEP 2: RESERVE_INVENTORY (cross-service REST calls to Catalog Service) ===
        OrderSagaState sagaState = retrieveSagaState(savedOrder.getOrderId());
        boolean inventoryReserved = executeStepReserveInventory(savedOrder, sagaState);

        if (!inventoryReserved) {
            // Order already marked FAILED inside executeStepReserveInventory
            return savedOrder;
        }

        // === STEP 3: CONFIRM_ORDER (local database update) ===
        return executeStepConfirmOrder(savedOrder, sagaState);
    }

    // -----------------------------------------------------------------------
    // Step 1: CREATE_ORDER — Local database writes
    // -----------------------------------------------------------------------

    /**
     * Saga Step 1: Creates the order record with {@code "PENDING"} status,
     * inserts the initial OrderStatus record (replicating the monolith's quirk
     * where {@code lineNum = orderId}), saves all line items, and persists the
     * saga state for crash recovery.
     *
     * <p>The PostgreSQL sequence {@code order_id_seq} auto-generates the orderId
     * — no call to the monolith's {@code getNextId("ordernum")} method is needed.
     * This eliminates the non-thread-safe read-then-update race condition that
     * existed in the monolith's sequence table pattern.</p>
     *
     * @param order the incoming Order with lineItems but without an orderId
     * @return the saved Order with the auto-generated orderId and {@code "PENDING"} status
     */
    private Order executeStepCreateOrder(Order order) {
        log.info("Saga Step 1 [CREATE_ORDER]: Creating order for user: {}", order.getUsername());

        // Save order with PENDING status — orderId auto-generated by PostgreSQL sequence
        order.setStatus("PENDING");
        Order savedOrder = orderRepository.save(order);

        // Explicitly save all line items to supplement cascade and ensure persistence
        // before cross-service REST calls; the cascade on @OneToMany CascadeType.ALL
        // handles persistence, but this explicit call provides traceability and
        // guarantees line items are flushed to the persistence context
        List<LineItem> lineItems = savedOrder.getLineItems();
        if (lineItems != null && !lineItems.isEmpty()) {
            lineItemRepository.saveAll(lineItems);
        }

        // Save OrderStatus record replicating the monolith's quirk:
        // In the monolith's OrderMapper.xml (lines 104-107), the INSERT INTO ORDERSTATUS
        // uses orderId for BOTH the ORDERID and LINENUM columns:
        //   INSERT INTO ORDERSTATUS (ORDERID, LINENUM, TIMESTAMP, STATUS)
        //   VALUES (#{orderId}, #{orderId}, #{orderDate}, #{status})
        // This quirk is replicated exactly to maintain data compatibility.
        OrderStatus orderStatus = new OrderStatus();
        orderStatus.setOrderId(savedOrder.getOrderId());
        orderStatus.setLineNum(savedOrder.getOrderId()); // CRITICAL QUIRK: lineNum = orderId
        orderStatus.setTimestamp(LocalDateTime.now());
        orderStatus.setStatus("P"); // "P" = Pending, matching monolith Order.initOrder() line 316
        orderStatusRepository.save(orderStatus);

        // Persist saga state for crash recovery — enables reconciliation job to
        // discover and complete or compensate stalled sagas on JVM restart
        OrderSagaState sagaState = new OrderSagaState(
                savedOrder.getOrderId(),
                OrderSagaStep.CREATE_ORDER,
                OrderSagaState.STATUS_PENDING);
        sagaStateRepository.save(sagaState);

        log.info("Saga Step 1 [CREATE_ORDER] complete: Order {} created with PENDING status, "
                        + "{} line items persisted, saga state {} recorded",
                savedOrder.getOrderId(),
                (lineItems != null ? lineItems.size() : 0),
                sagaState.getSagaId());

        return savedOrder;
    }

    // -----------------------------------------------------------------------
    // Step 2: RESERVE_INVENTORY — Cross-service REST calls
    // -----------------------------------------------------------------------

    /**
     * Saga Step 2: Reserves inventory for each line item by calling the Catalog
     * Service's REST API. Each call passes the {@code orderId} as an idempotency
     * key to prevent double-decrementing on retries.
     *
     * <p>If any inventory decrement fails:</p>
     * <ol>
     *   <li>Processing stops immediately (no further items are processed)</li>
     *   <li>Already-decremented items are compensated via
     *       {@link InventoryCompensation#compensate(Order, List)}</li>
     *   <li>The order status is updated to {@code "FAILED"}</li>
     *   <li>The saga state is updated to {@code "FAILED"}</li>
     * </ol>
     *
     * @param savedOrder the order with auto-generated orderId and persisted line items
     * @param sagaState  the current saga state entity (or {@code null} if not found)
     * @return {@code true} if all inventory was successfully reserved;
     *         {@code false} if any reservation failed (order already marked FAILED)
     */
    private boolean executeStepReserveInventory(Order savedOrder, OrderSagaState sagaState) {
        int orderId = savedOrder.getOrderId();
        log.info("Saga Step 2 [RESERVE_INVENTORY]: Reserving inventory for order {}", orderId);

        // Update saga state to RESERVE_INVENTORY step
        if (sagaState != null) {
            sagaState.setCurrentStep(OrderSagaStep.RESERVE_INVENTORY);
            sagaStateRepository.save(sagaState);
        }

        // Track successfully decremented items for potential compensation
        // Uses ArrayList for O(1) amortized append during sequential processing
        List<LineItem> decrementedItems = new ArrayList<>();
        boolean reservationSuccess = true;
        String failureReason = null;

        try {
            List<LineItem> lineItems = savedOrder.getLineItems();
            if (lineItems != null) {
                for (LineItem lineItem : lineItems) {
                    // Call Catalog Service REST API with orderId as idempotency key
                    // (per AAP Section 0.7.1: "Every inventory reservation request
                    // includes the orderId as an idempotency key. Catalog Service stores
                    // reservation records indexed by orderId and returns success for
                    // duplicate requests without double-decrementing.")
                    boolean success = catalogServiceClient.decrementInventory(
                            lineItem.getItemId(),
                            lineItem.getQuantity(),
                            String.valueOf(orderId));

                    if (success) {
                        decrementedItems.add(lineItem);
                        log.info("Inventory reserved for item: {}, qty: {}, order: {}",
                                lineItem.getItemId(), lineItem.getQuantity(), orderId);
                    } else {
                        // HTTP 409 Conflict — insufficient stock for this item
                        log.warn("Inventory reservation FAILED for item: {}, qty: {} "
                                        + "- insufficient stock (order {})",
                                lineItem.getItemId(), lineItem.getQuantity(), orderId);
                        reservationSuccess = false;
                        failureReason = "Insufficient inventory for item: " + lineItem.getItemId();
                        break; // Stop processing remaining items
                    }
                }
            }
        } catch (RuntimeException ex) {
            // HTTP 5xx or network error from CatalogServiceClient
            // The CatalogServiceClient throws RuntimeException on 5xx responses
            // and network failures — this catches those and enters compensation
            log.warn("Inventory reservation FAILED for order {} due to Catalog Service error: {}",
                    orderId, ex.getMessage());
            reservationSuccess = false;
            failureReason = "Catalog service error: " + ex.getMessage();
        }

        if (!reservationSuccess) {
            handleSagaCompensation(savedOrder, sagaState, decrementedItems, failureReason);
            return false;
        }

        // All inventory successfully reserved — update saga state
        if (sagaState != null) {
            sagaState.setStatus(OrderSagaState.STATUS_INVENTORY_RESERVED);
            sagaStateRepository.save(sagaState);
        }

        log.info("Saga Step 2 [RESERVE_INVENTORY] complete: All {} items reserved for order {}",
                decrementedItems.size(), orderId);
        return true;
    }

    // -----------------------------------------------------------------------
    // Step 3: CONFIRM_ORDER — Local database update
    // -----------------------------------------------------------------------

    /**
     * Saga Step 3: Confirms the order by updating its status from
     * {@code "PENDING"} to {@code "CONFIRMED"} and the saga state to
     * {@code "COMPLETED"}. This is the point of no return — after this step,
     * the order is final and inventory decrements are permanent.
     *
     * @param savedOrder the order to confirm
     * @param sagaState  the current saga state entity (or {@code null} if not found)
     * @return the confirmed Order with status {@code "CONFIRMED"}
     */
    private Order executeStepConfirmOrder(Order savedOrder, OrderSagaState sagaState) {
        int orderId = savedOrder.getOrderId();
        log.info("Saga Step 3 [CONFIRM_ORDER]: Confirming order {}", orderId);

        // Update order status to CONFIRMED — this is the point of no return
        savedOrder.setStatus("CONFIRMED");
        Order confirmedOrder = orderRepository.save(savedOrder);

        // Update saga state to final terminal state
        if (sagaState != null) {
            sagaState.setCurrentStep(OrderSagaStep.CONFIRM_ORDER);
            sagaState.setStatus(OrderSagaState.STATUS_COMPLETED);
            sagaStateRepository.save(sagaState);
        }

        log.info("Saga COMPLETE: Order {} confirmed successfully for user {}. "
                        + "Saga state: {}",
                orderId, confirmedOrder.getUsername(),
                (sagaState != null ? sagaState.getSagaId() : "N/A"));

        return confirmedOrder;
    }

    // -----------------------------------------------------------------------
    // Compensation — Failure handling and inventory restoration
    // -----------------------------------------------------------------------

    /**
     * Handles saga failure by compensating already-decremented inventory,
     * marking the order as {@code "FAILED"}, and updating the saga state.
     *
     * <p>Compensation is only attempted for items that were successfully
     * decremented before the failure occurred. If compensation itself fails
     * partially (some items cannot be restored), a CRITICAL error is logged
     * indicating that manual intervention is required to restore inventory
     * consistency.</p>
     *
     * @param savedOrder      the order being failed
     * @param sagaState       the current saga state (or {@code null})
     * @param decrementedItems list of line items that were successfully decremented
     *                         and need inventory restoration
     * @param failureReason   human-readable description of the failure cause
     */
    private void handleSagaCompensation(Order savedOrder, OrderSagaState sagaState,
                                         List<LineItem> decrementedItems, String failureReason) {
        int orderId = savedOrder.getOrderId();
        log.warn("Saga COMPENSATION triggered for order {}: {}", orderId, failureReason);

        // Transition saga state to COMPENSATING to indicate compensation in progress
        if (sagaState != null) {
            sagaState.setStatus(OrderSagaState.STATUS_COMPENSATING);
            sagaStateRepository.save(sagaState);
        }

        // Compensate already-decremented items by restoring inventory via REST calls
        // to the Catalog Service. The InventoryCompensation component handles individual
        // item restoration and never throws — it catches all exceptions internally and
        // reports results via CompensationResult.
        if (!decrementedItems.isEmpty()) {
            log.warn("Compensating {} already-decremented items for order {}",
                    decrementedItems.size(), orderId);

            InventoryCompensation.CompensationResult result =
                    inventoryCompensation.compensate(savedOrder, decrementedItems);

            if (result.hasFailures()) {
                // CRITICAL: Some items could not be restored — this creates an inventory
                // inconsistency that requires manual intervention. The Catalog Service has
                // decremented inventory for these items, but the order will be marked FAILED,
                // meaning the customer did not receive the items. An operations team member
                // must manually restore the inventory for the failed items.
                log.error("CRITICAL: Partial compensation failure for order {}. "
                                + "Failed to restore inventory for items: {}. "
                                + "Manual intervention required to restore inventory consistency.",
                        orderId, result.failedItemIds());
            } else {
                log.info("Compensation successful: All {} items inventory restored for order {}",
                        decrementedItems.size(), orderId);
            }
        } else {
            log.info("No compensation needed: No inventory was decremented for order {}", orderId);
        }

        // Mark the order as FAILED — this persists within the current transaction
        savedOrder.setStatus("FAILED");
        orderRepository.save(savedOrder);

        // Transition saga state to terminal FAILED state
        if (sagaState != null) {
            sagaState.setCurrentStep(OrderSagaStep.CONFIRM_ORDER);
            sagaState.setStatus(OrderSagaState.STATUS_FAILED);
            sagaStateRepository.save(sagaState);
        }

        log.warn("Saga FAILED: Order {} marked as FAILED. Reason: {}. "
                        + "Saga state: {}",
                orderId, failureReason,
                (sagaState != null ? sagaState.getSagaId() : "N/A"));
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Retrieves the saga state for the given order ID. Since an order may have
     * multiple saga records (e.g., from retries), this method returns the first
     * one found. In normal operation, there should be exactly one non-terminal
     * saga per order.
     *
     * @param orderId the order ID to look up
     * @return the first {@link OrderSagaState} found for this order, or
     *         {@code null} if none exists
     */
    private OrderSagaState retrieveSagaState(int orderId) {
        List<OrderSagaState> sagaStates = sagaStateRepository.findByOrderId(orderId);
        if (sagaStates.isEmpty()) {
            log.warn("No saga state found for order {} — saga state tracking degraded", orderId);
            return null;
        }
        return sagaStates.get(0);
    }
}
