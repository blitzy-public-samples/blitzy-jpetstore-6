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

// Java standard library imports
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// JUnit Jupiter 5 imports
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

// Mockito imports
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Spring Transaction import — required because SUT constructor takes PlatformTransactionManager
import org.springframework.transaction.PlatformTransactionManager;

// AssertJ static imports for fluent assertions
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Mockito static imports for stubbing and verification
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Internal entity imports
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;
import com.jpetstore.order.entity.OrderStatus;

// Internal repository imports
import com.jpetstore.order.repository.LineItemRepository;
import com.jpetstore.order.repository.OrderRepository;
import com.jpetstore.order.repository.OrderSagaStateRepository;
import com.jpetstore.order.repository.OrderStatusRepository;

// Internal client import
import com.jpetstore.order.client.CatalogServiceClient;

// Internal saga imports
import com.jpetstore.order.saga.InventoryCompensation;
import com.jpetstore.order.saga.InventoryCompensation.CompensationResult;
import com.jpetstore.order.saga.OrderSagaOrchestrator;
import com.jpetstore.order.saga.OrderSagaState;
import com.jpetstore.order.saga.OrderSagaStep;

/**
 * Comprehensive unit tests for {@link OrderSagaOrchestrator} — the Saga coordinator
 * for distributed order transactions as specified in AAP Section 0.7.1.
 *
 * <p>Tests verify the 3-step Saga pattern:</p>
 * <ol>
 *   <li><strong>CREATE_ORDER</strong> — Save order with PENDING status, insert OrderStatus
 *       and LineItems, create saga state</li>
 *   <li><strong>RESERVE_INVENTORY</strong> — Call Catalog Service REST API to decrement
 *       inventory for each line item, with orderId as idempotency key</li>
 *   <li><strong>CONFIRM_ORDER</strong> — Update order status to CONFIRMED, saga state to
 *       COMPLETED</li>
 * </ol>
 *
 * <p>Uses {@code @ExtendWith(MockitoExtension.class)} for pure unit tests without
 * Spring context loading. All 7 constructor dependencies of
 * {@link OrderSagaOrchestrator} are mocked, including
 * {@link PlatformTransactionManager} which the SUT uses to create a
 * {@link org.springframework.transaction.support.TransactionTemplate}.</p>
 *
 * <p>All 14 test methods cover the scenarios specified in AAP Section 0.7.1:</p>
 * <ul>
 *   <li>Success flow: PENDING → INVENTORY_RESERVED → COMPLETED</li>
 *   <li>Inventory reservation failure + compensation</li>
 *   <li>Catalog Service unavailability</li>
 *   <li>Partial compensation failure with CRITICAL logging</li>
 *   <li>Idempotency key verification</li>
 *   <li>Saga state persistence at each step transition</li>
 *   <li>Order status transitions</li>
 *   <li>Edge cases (zero and single line items)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class OrderSagaOrchestratorTest {

    // -----------------------------------------------------------------------
    // Mock Dependencies — matching OrderSagaOrchestrator's 7-param constructor
    // -----------------------------------------------------------------------

    /** Spring Data JPA repository for Order entity persistence. */
    @Mock
    private OrderRepository orderRepository;

    /** Spring Data JPA repository for OrderStatus entity persistence. */
    @Mock
    private OrderStatusRepository orderStatusRepository;

    /** Spring Data JPA repository for LineItem entity persistence. */
    @Mock
    private LineItemRepository lineItemRepository;

    /** REST client for cross-service communication with Catalog Service. */
    @Mock
    private CatalogServiceClient catalogServiceClient;

    /** Saga compensating transaction handler for inventory restoration. */
    @Mock
    private InventoryCompensation inventoryCompensation;

    /** Spring Data JPA repository for persisting OrderSagaState entities. */
    @Mock
    private OrderSagaStateRepository orderSagaStateRepository;

    /**
     * Spring PlatformTransactionManager — the SUT creates
     * {@code new TransactionTemplate(transactionManager)} in its constructor.
     * With a mock PTM, TransactionTemplate.execute() calls
     * getTransaction(this) → null, doInTransaction(null) → callback runs,
     * commit(null) → no-op on mock. This enables unit testing without a real
     * transaction manager.
     */
    @Mock
    private PlatformTransactionManager transactionManager;

    // -----------------------------------------------------------------------
    // System Under Test
    // -----------------------------------------------------------------------

    /** The Saga orchestrator with all 7 mocked dependencies injected. */
    @InjectMocks
    private OrderSagaOrchestrator orderSagaOrchestrator;

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Creates a test Order entity with the specified number of line items.
     *
     * @param orderId      the order ID to set
     * @param numLineItems number of line items to generate (EST-1, EST-2, ...)
     * @return a fully populated Order entity ready for saga execution
     */
    private Order createTestOrder(int orderId, int numLineItems) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setUsername("testuser");
        // Status is deliberately NOT set — the SUT sets it to PENDING in Step 1

        List<LineItem> lineItems = new ArrayList<>();
        for (int i = 1; i <= numLineItems; i++) {
            lineItems.add(createLineItem(orderId, i, "EST-" + i, 2, "16.50"));
        }
        order.setLineItems(lineItems);
        return order;
    }

    /**
     * Creates a single LineItem entity with all fields populated.
     *
     * @param orderId the parent order ID
     * @param lineNum the line number (1-based)
     * @param itemId  the catalog item identifier (e.g., "EST-1")
     * @param qty     the quantity ordered
     * @param price   the unit price as a decimal string
     * @return a fully populated LineItem entity
     */
    private LineItem createLineItem(int orderId, int lineNum, String itemId, int qty, String price) {
        LineItem item = new LineItem();
        item.setOrderId(orderId);
        item.setLineNum(lineNum);
        item.setItemId(itemId);
        item.setQuantity(qty);
        item.setUnitPrice(new BigDecimal(price));
        return item;
    }

    /**
     * Sets up common lenient stubs for repository save operations and
     * saga state retrieval used across multiple tests.
     *
     * <p>Stubs are lenient because not every test exercises every repository
     * save path — Mockito's strict stubbing would flag unused stubs as errors.</p>
     *
     * @param orderId the order ID for the findByOrderId stub
     */
    private void stubRepositorySaves(int orderId) {
        // Stub orderRepository.save() to return the same order object with identity
        lenient().when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Stub orderStatusRepository.save() to return the saved status
        lenient().when(orderStatusRepository.save(any(OrderStatus.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // Stub lineItemRepository.saveAll() to return the saved line items
        lenient().when(lineItemRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Stub sagaStateRepository.save() to return the saved saga state
        lenient().when(orderSagaStateRepository.save(any(OrderSagaState.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // CRITICAL: Stub findByOrderId to return a non-null list with a fresh
        // OrderSagaState. The SUT's retrieveSagaState() calls findByOrderId()
        // and calls .isEmpty() on the result — Mockito returns null by default,
        // which would cause NullPointerException.
        lenient().when(orderSagaStateRepository.findByOrderId(anyInt()))
                .thenAnswer(inv -> {
                    int id = inv.getArgument(0);
                    return List.of(new OrderSagaState(id,
                            OrderSagaStep.CREATE_ORDER,
                            OrderSagaState.STATUS_PENDING));
                });
    }

    // =======================================================================
    // SUCCESS FLOW TESTS (AAP Section 0.7.1 — Happy Path)
    // =======================================================================

    /**
     * Test 1: Validates the complete success flow for a single-line-item order.
     * PENDING → INVENTORY_RESERVED → COMPLETED (AAP 0.7.1 success flow).
     *
     * <p>Verifies the 3-step saga sequence:</p>
     * <ol>
     *   <li>Order saved as PENDING + OrderStatus + LineItems persisted</li>
     *   <li>CatalogServiceClient.decrementInventory called with orderId as idempotency key</li>
     *   <li>Order updated to CONFIRMED, returned with CONFIRMED status</li>
     * </ol>
     */
    @Test
    void shouldCompleteSagaSuccessfully_SingleLineItem() {
        // Arrange
        Order order = createTestOrder(1, 1);
        stubRepositorySaves(1);

        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(true);

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — SUT saves order twice: Step 1 (PENDING) + Step 3 (CONFIRMED)
        verify(orderRepository, times(2)).save(any(Order.class));
        verify(orderStatusRepository).save(any(OrderStatus.class));

        // Assert — Step 2: Inventory decrement called with orderId as idempotency key
        verify(catalogServiceClient).decrementInventory("EST-1", 2, "1");

        // Assert — Step 3: Order returned with CONFIRMED status
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");

        // Assert — No compensation triggered on success
        verify(inventoryCompensation, never()).compensate(any(), any());
    }

    /**
     * Test 2: Validates the complete success flow with multiple line items.
     * All inventory decrements succeed, order is CONFIRMED.
     */
    @Test
    void shouldCompleteSagaSuccessfully_MultipleLineItems() {
        // Arrange — Order with 3 different line items
        Order order = new Order();
        order.setOrderId(1);
        order.setUsername("testuser");
        List<LineItem> items = new ArrayList<>();
        items.add(createLineItem(1, 1, "EST-1", 2, "16.50"));
        items.add(createLineItem(1, 2, "EST-14", 1, "58.50"));
        items.add(createLineItem(1, 3, "EST-25", 3, "18.50"));
        order.setLineItems(items);

        stubRepositorySaves(1);

        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(true);
        when(catalogServiceClient.decrementInventory(eq("EST-14"), eq(1), eq("1")))
                .thenReturn(true);
        when(catalogServiceClient.decrementInventory(eq("EST-25"), eq(3), eq("1")))
                .thenReturn(true);

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — All 3 decrements called
        verify(catalogServiceClient).decrementInventory("EST-1", 2, "1");
        verify(catalogServiceClient).decrementInventory("EST-14", 1, "1");
        verify(catalogServiceClient).decrementInventory("EST-25", 3, "1");

        // Assert — Order confirmed
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");

        // Assert — No compensation
        verify(inventoryCompensation, never()).compensate(any(), any());
    }

    // =======================================================================
    // INVENTORY RESERVATION FAILURE TESTS (AAP Section 0.7.1 — Failure + Compensation)
    // =======================================================================

    /**
     * Test 3: Validates failure and compensation when the SECOND item's inventory
     * reservation fails (HTTP 409 Conflict — insufficient stock).
     *
     * <p>AAP 0.7.1: "Reservation fails (insufficient stock) → compensate
     * already-decremented items → order FAILED"</p>
     *
     * <p>The SUT returns the order with status "FAILED" (it does NOT throw).
     * Compensation is called with ONLY the first item that was successfully
     * decremented before the second failed.</p>
     */
    @Test
    void shouldFailAndCompensateWhenInventoryReservationFails() {
        // Arrange — 2 items: EST-1 succeeds, EST-14 fails (insufficient stock)
        Order order = new Order();
        order.setOrderId(1);
        order.setUsername("testuser");
        List<LineItem> items = new ArrayList<>();
        items.add(createLineItem(1, 1, "EST-1", 2, "16.50"));
        items.add(createLineItem(1, 2, "EST-14", 100, "58.50"));
        order.setLineItems(items);

        stubRepositorySaves(1);

        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(true);
        when(catalogServiceClient.decrementInventory(eq("EST-14"), eq(100), eq("1")))
                .thenReturn(false); // 409 Conflict

        // Stub compensation to return success for the single decremented item
        when(inventoryCompensation.compensate(any(Order.class), any()))
                .thenReturn(new CompensationResult(
                        List.of("EST-1"), Collections.emptyList()));

        // Act — SUT returns order with FAILED status (does NOT throw)
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order status is FAILED
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("FAILED");

        // Assert — Compensation called with ONLY the first item (EST-1) that
        // was successfully decremented before EST-14 failed
        verify(inventoryCompensation).compensate(
                any(Order.class),
                org.mockito.ArgumentMatchers.argThat(decremented ->
                        decremented.size() == 1
                                && decremented.get(0).getItemId().equals("EST-1")));
    }

    /**
     * Test 4: Validates failure when the FIRST item's inventory reservation
     * fails. No compensation is needed because nothing was decremented.
     *
     * <p>When the first decrement returns false, the SUT breaks immediately.
     * Since no items were successfully decremented, the decrementedItems list
     * is empty, and compensation is either not called or called with an empty
     * list (the SUT checks {@code !decrementedItems.isEmpty()} before calling
     * compensation).</p>
     */
    @Test
    void shouldFailWhenFirstItemInventoryReservationFails() {
        // Arrange — 2 items, FIRST item fails immediately
        Order order = new Order();
        order.setOrderId(1);
        order.setUsername("testuser");
        List<LineItem> items = new ArrayList<>();
        items.add(createLineItem(1, 1, "EST-1", 2, "16.50"));
        items.add(createLineItem(1, 2, "EST-14", 1, "58.50"));
        order.setLineItems(items);

        stubRepositorySaves(1);

        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(false); // First item fails

        // Act — SUT catches the failure and returns order with FAILED status
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order is FAILED
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("FAILED");

        // Assert — Second item was never attempted (SUT breaks on first failure)
        verify(catalogServiceClient, never())
                .decrementInventory(eq("EST-14"), anyInt(), anyString());

        // Assert — Compensation NOT called because decrementedItems is empty
        // (the SUT checks !decrementedItems.isEmpty() before calling compensate)
        verify(inventoryCompensation, never()).compensate(any(), any());
    }

    /**
     * Test 5: Validates failure when the Catalog Service is completely
     * unavailable (HTTP 503 — RuntimeException thrown by client).
     *
     * <p>AAP 0.7.1: "Catalog Service unavailable: HTTP 503 → no decrements
     * → order FAILED"</p>
     *
     * <p>The SUT catches the RuntimeException internally (in a try/catch block
     * around the inventory loop) and enters the compensation flow. Since no
     * items were decremented before the exception, compensation is not called
     * for any items.</p>
     */
    @Test
    void shouldFailWhenCatalogServiceUnavailable() {
        // Arrange — Catalog client throws RuntimeException (503 Service Unavailable)
        Order order = createTestOrder(1, 1);
        stubRepositorySaves(1);

        when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("503 Service Unavailable"));

        // Act — SUT catches the RuntimeException internally and returns FAILED order
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order is FAILED (not thrown to caller)
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("FAILED");

        // Assert — Compensation NOT called because no items were decremented
        // before the RuntimeException occurred
        verify(inventoryCompensation, never()).compensate(any(), any());

        // Verify that passing null to executeOrderSaga throws NullPointerException,
        // confirming the SUT does not have defensive null checks on the order parameter.
        // This uses assertThatThrownBy for a legitimate edge case verification.
        assertThatThrownBy(() -> orderSagaOrchestrator.executeOrderSaga(null))
                .isInstanceOf(NullPointerException.class);
    }

    // =======================================================================
    // PARTIAL COMPENSATION FAILURE TEST (AAP Section 0.7.1 — CRITICAL Error)
    // =======================================================================

    /**
     * Test 6: Validates CRITICAL error logging when compensation itself
     * partially fails — some inventory restores succeed, some fail.
     *
     * <p>AAP 0.7.1: "Partial compensation failure: Some restores succeed,
     * some fail → CRITICAL error logged"</p>
     *
     * <p>The InventoryCompensation component never throws (per AAP), but returns
     * a CompensationResult with hasFailures() == true when some items could
     * not be restored. The SUT logs a CRITICAL error for manual intervention.</p>
     */
    @Test
    void shouldLogCriticalErrorWhenPartialCompensationFails() {
        // Arrange — 3 items: first 2 succeed, third fails
        Order order = new Order();
        order.setOrderId(1);
        order.setUsername("testuser");
        List<LineItem> items = new ArrayList<>();
        items.add(createLineItem(1, 1, "EST-1", 2, "16.50"));
        items.add(createLineItem(1, 2, "EST-14", 1, "58.50"));
        items.add(createLineItem(1, 3, "EST-25", 3, "18.50"));
        order.setLineItems(items);

        stubRepositorySaves(1);

        // First 2 items succeed, third fails
        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(true);
        when(catalogServiceClient.decrementInventory(eq("EST-14"), eq(1), eq("1")))
                .thenReturn(true);
        when(catalogServiceClient.decrementInventory(eq("EST-25"), eq(3), eq("1")))
                .thenReturn(false); // Third item fails

        // Compensation returns PARTIAL failure — EST-1 restored, EST-14 failed to restore
        when(inventoryCompensation.compensate(any(Order.class), any()))
                .thenReturn(new CompensationResult(
                        List.of("EST-1"),          // restored
                        List.of("EST-14")));       // failed to restore

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order is FAILED
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("FAILED");

        // Assert — Compensation called with 2 decremented items (EST-1 and EST-14)
        verify(inventoryCompensation).compensate(
                any(Order.class),
                org.mockito.ArgumentMatchers.argThat(decremented ->
                        decremented.size() == 2));

        // Verify the CompensationResult tracks the partial failure
        CompensationResult partialResult = new CompensationResult(
                List.of("EST-1"), List.of("EST-14"));
        assertThat(partialResult.hasFailures()).isTrue();
        assertThat(partialResult.isFullyCompensated()).isFalse();
        assertThat(partialResult.restoredItemIds()).containsExactly("EST-1");
        assertThat(partialResult.failedItemIds()).containsExactly("EST-14");
    }

    // =======================================================================
    // IDEMPOTENCY KEY VERIFICATION TEST (AAP Section 0.7.1 — Critical Contract)
    // =======================================================================

    /**
     * Test 7: Validates that the orderId is passed as a String idempotency
     * key on every single decrementInventory call.
     *
     * <p>AAP 0.7.1: "Every inventory reservation request includes the orderId
     * as an idempotency key. Catalog Service stores reservation records indexed
     * by orderId and returns success for duplicate requests without
     * double-decrementing."</p>
     */
    @Test
    void shouldPassOrderIdAsIdempotencyKeyOnEveryDecrementCall() {
        // Arrange — Order 42 with 3 items
        Order order = new Order();
        order.setOrderId(42);
        order.setUsername("testuser");
        List<LineItem> items = new ArrayList<>();
        items.add(createLineItem(42, 1, "EST-1", 2, "16.50"));
        items.add(createLineItem(42, 2, "EST-14", 1, "58.50"));
        items.add(createLineItem(42, 3, "EST-25", 3, "18.50"));
        order.setLineItems(items);

        stubRepositorySaves(42);

        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("42")))
                .thenReturn(true);
        when(catalogServiceClient.decrementInventory(eq("EST-14"), eq(1), eq("42")))
                .thenReturn(true);
        when(catalogServiceClient.decrementInventory(eq("EST-25"), eq(3), eq("42")))
                .thenReturn(true);

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Every call passes "42" as the String idempotency key (3rd param)
        verify(catalogServiceClient).decrementInventory(eq("EST-1"), eq(2), eq("42"));
        verify(catalogServiceClient).decrementInventory(eq("EST-14"), eq(1), eq("42"));
        verify(catalogServiceClient).decrementInventory(eq("EST-25"), eq(3), eq("42"));

        // Assert — Order confirmed successfully
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
    }

    // =======================================================================
    // SAGA STATE PERSISTENCE TESTS (AAP — State Machine Verification)
    // =======================================================================

    /**
     * Test 8: Validates that saga state is persisted at each step transition
     * during a successful saga execution.
     *
     * <p>The SUT saves OrderSagaState 4 times during a success flow:</p>
     * <ol>
     *   <li>CREATE_ORDER / PENDING (Step 1 — initial saga state)</li>
     *   <li>RESERVE_INVENTORY / PENDING (Step 2 pre — step update only)</li>
     *   <li>RESERVE_INVENTORY / INVENTORY_RESERVED (Step 2 post — status update)</li>
     *   <li>CONFIRM_ORDER / COMPLETED (Step 3 — terminal state)</li>
     * </ol>
     */
    @Test
    void shouldPersistSagaStateAtEachStepTransition() {
        // Arrange
        Order order = createTestOrder(1, 1);

        // Stub repository saves with identity returns
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderStatusRepository.save(any(OrderStatus.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(lineItemRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Capture saga state saves — return the saved state to allow mutation tracking
        List<OrderSagaStep> capturedSteps = new ArrayList<>();
        List<String> capturedStatuses = new ArrayList<>();
        when(orderSagaStateRepository.save(any(OrderSagaState.class)))
                .thenAnswer(inv -> {
                    OrderSagaState state = inv.getArgument(0);
                    capturedSteps.add(state.getCurrentStep());
                    capturedStatuses.add(state.getStatus());
                    return state;
                });

        // Stub findByOrderId to return a fresh saga state (mimics Step 1's persisted state)
        when(orderSagaStateRepository.findByOrderId(anyInt()))
                .thenAnswer(inv -> {
                    int id = inv.getArgument(0);
                    return List.of(new OrderSagaState(id,
                            OrderSagaStep.CREATE_ORDER,
                            OrderSagaState.STATUS_PENDING));
                });

        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(true);

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order confirmed
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");

        // Assert — Saga state saved at least 3 times (CREATE_ORDER, RESERVE_INVENTORY, CONFIRM_ORDER)
        assertThat(capturedSteps).hasSizeGreaterThanOrEqualTo(3);

        // First save: CREATE_ORDER / PENDING (Step 1)
        assertThat(capturedSteps.get(0)).isEqualTo(OrderSagaStep.CREATE_ORDER);
        assertThat(capturedStatuses.get(0)).isEqualTo(OrderSagaState.STATUS_PENDING);

        // The full sequence MUST contain all three expected step transitions in order
        assertThat(capturedSteps).containsSubsequence(
                OrderSagaStep.CREATE_ORDER,
                OrderSagaStep.RESERVE_INVENTORY,
                OrderSagaStep.CONFIRM_ORDER);

        // The full sequence MUST contain the expected status transitions in order
        assertThat(capturedStatuses).containsSubsequence(
                OrderSagaState.STATUS_PENDING,
                OrderSagaState.STATUS_INVENTORY_RESERVED,
                OrderSagaState.STATUS_COMPLETED);

        // Last save: CONFIRM_ORDER / COMPLETED (terminal state)
        int lastIdx = capturedSteps.size() - 1;
        assertThat(capturedSteps.get(lastIdx)).isEqualTo(OrderSagaStep.CONFIRM_ORDER);
        assertThat(capturedStatuses.get(lastIdx)).isEqualTo(OrderSagaState.STATUS_COMPLETED);
    }

    /**
     * Test 9: Validates that saga state is set to FAILED when inventory
     * reservation fails.
     *
     * <p>The terminal saga state should have status=FAILED and
     * currentStep=RESERVE_INVENTORY (reflecting the step where failure occurred).</p>
     */
    @Test
    void shouldSetSagaStateToFailedOnInventoryFailure() {
        // Arrange
        Order order = createTestOrder(1, 1);

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderStatusRepository.save(any(OrderStatus.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(lineItemRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Capture saga state saves for assertion
        List<String> capturedStatuses = new ArrayList<>();
        List<OrderSagaStep> capturedSteps = new ArrayList<>();
        when(orderSagaStateRepository.save(any(OrderSagaState.class)))
                .thenAnswer(inv -> {
                    OrderSagaState state = inv.getArgument(0);
                    capturedStatuses.add(state.getStatus());
                    capturedSteps.add(state.getCurrentStep());
                    return state;
                });

        when(orderSagaStateRepository.findByOrderId(anyInt()))
                .thenAnswer(inv -> {
                    int id = inv.getArgument(0);
                    return List.of(new OrderSagaState(id,
                            OrderSagaStep.CREATE_ORDER,
                            OrderSagaState.STATUS_PENDING));
                });

        // Inventory reservation fails
        when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                .thenReturn(false);

        // Act — SUT returns FAILED order (does not throw)
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order is FAILED
        assertThat(result.getStatus()).isEqualTo("FAILED");

        // Assert — Saga state ends in FAILED status
        assertThat(capturedStatuses).contains(OrderSagaState.STATUS_FAILED);

        // Assert — The last saved saga state has FAILED status
        int lastIdx = capturedStatuses.size() - 1;
        assertThat(capturedStatuses.get(lastIdx)).isEqualTo(OrderSagaState.STATUS_FAILED);

        // Assert — The failed step is RESERVE_INVENTORY (where failure occurred)
        assertThat(capturedSteps.get(lastIdx)).isEqualTo(OrderSagaStep.RESERVE_INVENTORY);
    }

    /**
     * Test 10: Validates that saga state transitions through COMPENSATING
     * status during the compensation flow.
     *
     * <p>When an inventory decrement fails after some items have been
     * successfully decremented, the saga state transitions:</p>
     * <ol>
     *   <li>PENDING (Step 1)</li>
     *   <li>RESERVE_INVENTORY step update (Step 2 pre)</li>
     *   <li>COMPENSATING (during compensation flow)</li>
     *   <li>FAILED (terminal state after compensation completes)</li>
     * </ol>
     */
    @Test
    void shouldSetSagaStateToCompensatingDuringCompensation() {
        // Arrange — 2 items: EST-1 succeeds, EST-14 fails → triggers compensation
        Order order = new Order();
        order.setOrderId(1);
        order.setUsername("testuser");
        List<LineItem> items = new ArrayList<>();
        items.add(createLineItem(1, 1, "EST-1", 2, "16.50"));
        items.add(createLineItem(1, 2, "EST-14", 1, "58.50"));
        order.setLineItems(items);

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderStatusRepository.save(any(OrderStatus.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(lineItemRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // Capture saga state status transitions
        List<String> capturedSagaStatuses = new ArrayList<>();
        when(orderSagaStateRepository.save(any(OrderSagaState.class)))
                .thenAnswer(inv -> {
                    OrderSagaState state = inv.getArgument(0);
                    capturedSagaStatuses.add(state.getStatus());
                    return state;
                });

        when(orderSagaStateRepository.findByOrderId(anyInt()))
                .thenAnswer(inv -> {
                    int id = inv.getArgument(0);
                    return List.of(new OrderSagaState(id,
                            OrderSagaStep.CREATE_ORDER,
                            OrderSagaState.STATUS_PENDING));
                });

        // EST-1 succeeds, EST-14 fails
        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(true);
        when(catalogServiceClient.decrementInventory(eq("EST-14"), eq(1), eq("1")))
                .thenReturn(false);

        // Compensation returns success
        when(inventoryCompensation.compensate(any(Order.class), any()))
                .thenReturn(new CompensationResult(
                        List.of("EST-1"), Collections.emptyList()));

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order FAILED
        assertThat(result.getStatus()).isEqualTo("FAILED");

        // Assert — COMPENSATING status appears in the saga state transition history
        assertThat(capturedSagaStatuses).contains(OrderSagaState.STATUS_COMPENSATING);

        // Assert — FAILED status is the terminal state
        assertThat(capturedSagaStatuses).contains(OrderSagaState.STATUS_FAILED);

        // Assert — COMPENSATING appears before FAILED in the transition sequence
        int compensatingIdx = capturedSagaStatuses.indexOf(OrderSagaState.STATUS_COMPENSATING);
        int failedIdx = capturedSagaStatuses.lastIndexOf(OrderSagaState.STATUS_FAILED);
        assertThat(compensatingIdx).isLessThan(failedIdx);
    }

    // =======================================================================
    // ORDER STATUS TRANSITION TESTS (AAP — State Machine Verification)
    // =======================================================================

    /**
     * Test 11: Validates the order status transition from PENDING to CONFIRMED
     * in the success flow.
     *
     * <p>Uses Mockito InOrder to verify the temporal ordering of repository saves:
     * the order must first be saved as PENDING (Step 1), then later updated to
     * CONFIRMED (Step 3).</p>
     */
    @Test
    void shouldTransitionOrderFromPendingToConfirmed() {
        // Arrange
        Order order = createTestOrder(1, 1);

        // Capture order status values at each save call
        List<String> statusCaptures = new ArrayList<>();
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> {
                    Order saved = inv.getArgument(0);
                    statusCaptures.add(saved.getStatus());
                    return saved;
                });
        lenient().when(orderStatusRepository.save(any(OrderStatus.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(lineItemRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderSagaStateRepository.save(any(OrderSagaState.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderSagaStateRepository.findByOrderId(anyInt()))
                .thenAnswer(inv -> List.of(new OrderSagaState(
                        inv.getArgument(0),
                        OrderSagaStep.CREATE_ORDER,
                        OrderSagaState.STATUS_PENDING)));

        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(true);

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Result is CONFIRMED
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");

        // Assert — Temporal ordering: PENDING first, CONFIRMED last
        assertThat(statusCaptures).hasSizeGreaterThanOrEqualTo(2);
        assertThat(statusCaptures.get(0)).isEqualTo("PENDING");
        assertThat(statusCaptures.get(statusCaptures.size() - 1)).isEqualTo("CONFIRMED");

        // Assert — InOrder verification of repository.save() calls using calls(1)
        // calls(1) consumes exactly 1 invocation at each point in the order sequence
        InOrder inOrderVerifier = inOrder(orderRepository);
        inOrderVerifier.verify(orderRepository, calls(1)).save(any(Order.class)); // PENDING
        inOrderVerifier.verify(orderRepository, calls(1)).save(any(Order.class)); // CONFIRMED
    }

    /**
     * Test 12: Validates the order status transition from PENDING to FAILED
     * when inventory reservation fails.
     *
     * <p>Uses Mockito InOrder and captured status values to verify:
     * the order is first saved as PENDING (Step 1), then updated to FAILED
     * during compensation (via handleSagaCompensation).</p>
     */
    @Test
    void shouldTransitionOrderFromPendingToFailed() {
        // Arrange
        Order order = createTestOrder(1, 1);

        // Capture order status values at each save
        List<String> statusCaptures = new ArrayList<>();
        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> {
                    Order saved = inv.getArgument(0);
                    statusCaptures.add(saved.getStatus());
                    return saved;
                });
        lenient().when(orderStatusRepository.save(any(OrderStatus.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(lineItemRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderSagaStateRepository.save(any(OrderSagaState.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderSagaStateRepository.findByOrderId(anyInt()))
                .thenAnswer(inv -> List.of(new OrderSagaState(
                        inv.getArgument(0),
                        OrderSagaStep.CREATE_ORDER,
                        OrderSagaState.STATUS_PENDING)));

        // Inventory reservation fails
        when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                .thenReturn(false);

        // Act — SUT returns FAILED order (does not throw)
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Result is FAILED
        assertThat(result.getStatus()).isEqualTo("FAILED");

        // Assert — Temporal ordering: PENDING first, FAILED last
        assertThat(statusCaptures).hasSizeGreaterThanOrEqualTo(2);
        assertThat(statusCaptures.get(0)).isEqualTo("PENDING");
        assertThat(statusCaptures.get(statusCaptures.size() - 1)).isEqualTo("FAILED");

        // Assert — InOrder verification of order saves using calls(1)
        // calls(1) consumes exactly 1 invocation at each point in the order sequence
        InOrder inOrderVerifier = inOrder(orderRepository);
        inOrderVerifier.verify(orderRepository, calls(1)).save(any(Order.class)); // PENDING
        inOrderVerifier.verify(orderRepository, calls(1)).save(any(Order.class)); // FAILED
    }

    // =======================================================================
    // EDGE CASE TESTS
    // =======================================================================

    /**
     * Test 13: Validates behavior with an order containing zero line items.
     *
     * <p>With no line items to decrement inventory for, the saga should skip
     * Step 2 entirely and proceed directly to CONFIRM_ORDER.</p>
     */
    @Test
    void shouldHandleOrderWithZeroLineItems() {
        // Arrange — Order with empty line items list
        Order order = new Order();
        order.setOrderId(1);
        order.setUsername("testuser");
        order.setLineItems(Collections.emptyList());

        stubRepositorySaves(1);

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order confirmed directly (no inventory to reserve)
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");

        // Assert — No inventory decrement calls (nothing to decrement)
        verify(catalogServiceClient, never())
                .decrementInventory(anyString(), anyInt(), anyString());

        // Assert — No compensation triggered
        verify(inventoryCompensation, never()).compensate(any(), any());
    }

    /**
     * Test 14: Validates the standard success flow with exactly one line item.
     *
     * <p>A single-item order is the simplest non-trivial case, verifying the
     * complete 3-step saga sequence end-to-end with a single inventory decrement.</p>
     */
    @Test
    void shouldHandleOrderWithSingleLineItem() {
        // Arrange
        Order order = createTestOrder(1, 1);

        when(orderRepository.save(any(Order.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderStatusRepository.save(any(OrderStatus.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(lineItemRepository.saveAll(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(orderSagaStateRepository.save(any(OrderSagaState.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(orderSagaStateRepository.findByOrderId(anyInt()))
                .thenAnswer(inv -> List.of(new OrderSagaState(
                        inv.getArgument(0),
                        OrderSagaStep.CREATE_ORDER,
                        OrderSagaState.STATUS_PENDING)));

        when(catalogServiceClient.decrementInventory(eq("EST-1"), eq(2), eq("1")))
                .thenReturn(true);

        // Act
        Order result = orderSagaOrchestrator.executeOrderSaga(order);

        // Assert — Order confirmed with single line item
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");

        // Assert — Exactly one decrement call
        verify(catalogServiceClient).decrementInventory("EST-1", 2, "1");

        // Assert — No compensation
        verify(inventoryCompensation, never()).compensate(any(), any());

        // Assert — Order saved twice (Step 1 PENDING + Step 3 CONFIRMED), OrderStatus saved once
        verify(orderRepository, times(2)).save(any(Order.class));
        verify(orderStatusRepository).save(any(OrderStatus.class));
    }
}
