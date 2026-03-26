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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;
import com.jpetstore.order.entity.OrderStatus;
import com.jpetstore.order.repository.LineItemRepository;
import com.jpetstore.order.repository.OrderRepository;
import com.jpetstore.order.repository.OrderSagaStateRepository;
import com.jpetstore.order.repository.OrderStatusRepository;

/**
 * Unit test for {@link OrderSagaOrchestrator} — the most critical component in the
 * JPetStore monolith-to-microservices decomposition.
 *
 * <p>Tests the three-step orchestration-based Saga pattern for distributed order
 * transactions, including:</p>
 * <ul>
 *   <li>Successful 3-step saga (CREATE_ORDER → RESERVE_INVENTORY → CONFIRM_ORDER)</li>
 *   <li>Inventory reservation failure with full compensation</li>
 *   <li>Partial inventory reservation failure (some items succeed, then one fails)</li>
 *   <li>Catalog Service unreachable (RuntimeException during REST calls)</li>
 *   <li>Idempotency key (orderId) passed as String on every decrement call</li>
 *   <li>Saga state machine transitions: PENDING → COMPLETED and PENDING → FAILED</li>
 *   <li>Empty line items edge case</li>
 * </ul>
 *
 * <p>Uses a manual {@link PlatformTransactionManager} stub that executes
 * callbacks synchronously, simulating transaction commit behavior without a
 * real database. This allows verification that Step 1 commits independently
 * before Step 2 begins (AAP §0.7.1 durable record requirement).</p>
 *
 * @see OrderSagaOrchestrator
 * @see OrderSagaState
 * @see InventoryCompensation
 */
@ExtendWith(MockitoExtension.class)
class OrderSagaOrchestratorTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusRepository orderStatusRepository;

    @Mock
    private LineItemRepository lineItemRepository;

    @Mock
    private CatalogServiceClient catalogServiceClient;

    @Mock
    private InventoryCompensation inventoryCompensation;

    @Mock
    private OrderSagaStateRepository sagaStateRepository;

    private OrderSagaOrchestrator orchestrator;

    /**
     * Stub PlatformTransactionManager that executes TransactionTemplate callbacks
     * synchronously without a real database. Tracks how many commits occurred so
     * tests can verify independent transaction boundaries per saga step.
     */
    private int commitCount;

    @BeforeEach
    void setUp() {
        commitCount = 0;

        // Create a stub PlatformTransactionManager that records commits
        PlatformTransactionManager stubTransactionManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
                return new SimpleTransactionStatus(true);
            }

            @Override
            public void commit(TransactionStatus status) {
                commitCount++;
            }

            @Override
            public void rollback(TransactionStatus status) {
                // No-op for test purposes
            }
        };

        orchestrator = new OrderSagaOrchestrator(
                orderRepository,
                orderStatusRepository,
                lineItemRepository,
                catalogServiceClient,
                inventoryCompensation,
                sagaStateRepository,
                stubTransactionManager);

        // Default: orderRepository.saveAndFlush returns the order with an assigned orderId.
        // The orchestrator uses saveAndFlush() at Step 1 (line 257) to ensure the order ID
        // is generated by the PostgreSQL sequence before proceeding to Step 2.
        lenient().when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getOrderId() == 0) {
                order.setOrderId(1001); // Simulate PostgreSQL sequence-generated ID
            }
            return order;
        });

        // Default: orderRepository.save returns the order as-is (used for status updates
        // in Steps 3 — CONFIRMED/FAILED — after the initial saveAndFlush in Step 1)
        lenient().when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            return invocation.getArgument(0);
        });

        // Default: sagaStateRepository.findByOrderId returns a saga state
        lenient().when(sagaStateRepository.findByOrderId(anyInt())).thenAnswer(invocation -> {
            int orderId = invocation.getArgument(0);
            OrderSagaState state = new OrderSagaState(orderId, OrderSagaStep.CREATE_ORDER,
                    OrderSagaState.STATUS_PENDING);
            state.setSagaId("test-saga-" + orderId);
            return List.of(state);
        });

        // Default: sagaStateRepository.save returns the entity as-is
        lenient().when(sagaStateRepository.save(any(OrderSagaState.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Default: orderStatusRepository.save returns the entity
        lenient().when(orderStatusRepository.save(any(OrderStatus.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Default: lineItemRepository.saveAll returns the list
        lenient().when(lineItemRepository.saveAll(anyList()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // -----------------------------------------------------------------------
    // Helper methods
    // -----------------------------------------------------------------------

    /**
     * Creates a test Order with the given number of line items.
     * Each line item has a unique itemId and quantity=1.
     */
    private Order createTestOrder(int lineItemCount) {
        Order order = new Order();
        order.setUsername("testuser");
        order.setStatus("NEW");
        List<LineItem> lineItems = new ArrayList<>();
        for (int i = 0; i < lineItemCount; i++) {
            LineItem li = new LineItem();
            li.setItemId("ITEM-" + (i + 1));
            li.setQuantity(i + 1); // varying quantities
            li.setUnitPrice(new BigDecimal("10.00"));
            lineItems.add(li);
        }
        order.setLineItems(lineItems);
        return order;
    }

    // -----------------------------------------------------------------------
    // Success Scenarios
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Successful Saga Execution")
    class SuccessScenarios {

        @Test
        @DisplayName("3-step saga completes successfully: PENDING → CONFIRMED")
        void executeOrderSaga_allStepsSucceed_returnsConfirmedOrder() {
            // Given: order with 3 line items, all inventory decrements succeed
            Order order = createTestOrder(3);
            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenReturn(true);

            // Capture order status snapshots at each save/saveAndFlush (same object is mutated,
            // so ArgumentCaptor would show final state for all captures).
            // Step 1 uses saveAndFlush() (PENDING), Step 3 uses save() (CONFIRMED).
            List<String> orderStatusSnapshots = new ArrayList<>();
            when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
                Order o = invocation.getArgument(0);
                orderStatusSnapshots.add(o.getStatus());
                if (o.getOrderId() == 0) {
                    o.setOrderId(1001);
                }
                return o;
            });
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
                Order o = invocation.getArgument(0);
                orderStatusSnapshots.add(o.getStatus());
                return o;
            });

            // When
            Order result = orchestrator.executeOrderSaga(order);

            // Then: order status is CONFIRMED
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("CONFIRMED");
            assertThat(result.getOrderId()).isEqualTo(1001);

            // Verify order saved twice: first PENDING (Step 1), then CONFIRMED (Step 3)
            assertThat(orderStatusSnapshots).containsExactly("PENDING", "CONFIRMED");

            // Verify Step 1: OrderStatus record created
            verify(orderStatusRepository).save(any(OrderStatus.class));

            // Verify Step 1: line items saved
            verify(lineItemRepository).saveAll(any());

            // Verify saga state was persisted 4 times across the saga lifecycle
            verify(sagaStateRepository, times(4)).save(any(OrderSagaState.class));

            // Verify Step 2: all 3 items had inventory decremented
            verify(catalogServiceClient, times(3))
                    .decrementInventory(anyString(), anyInt(), eq("1001"));

            // Verify no compensation was triggered
            verify(inventoryCompensation, never()).compensate(any(Order.class), anyList());
        }

        @Test
        @DisplayName("Order with empty line items completes without inventory calls")
        void executeOrderSaga_noLineItems_completesWithoutInventoryCalls() {
            // Given: order with no line items
            Order order = new Order();
            order.setUsername("testuser");
            order.setStatus("NEW");
            order.setLineItems(Collections.emptyList());

            // When
            Order result = orchestrator.executeOrderSaga(order);

            // Then: order is confirmed (no inventory to reserve)
            assertThat(result.getStatus()).isEqualTo("CONFIRMED");

            // Verify no inventory calls were made
            verify(catalogServiceClient, never())
                    .decrementInventory(anyString(), anyInt(), anyString());

            // Verify no compensation was triggered
            verify(inventoryCompensation, never()).compensate(any(Order.class), anyList());
        }

        @Test
        @DisplayName("Single line item order succeeds")
        void executeOrderSaga_singleLineItem_succeeds() {
            // Given: order with 1 line item
            Order order = createTestOrder(1);
            when(catalogServiceClient.decrementInventory(eq("ITEM-1"), eq(1), anyString()))
                    .thenReturn(true);

            // When
            Order result = orchestrator.executeOrderSaga(order);

            // Then
            assertThat(result.getStatus()).isEqualTo("CONFIRMED");
            verify(catalogServiceClient, times(1))
                    .decrementInventory(eq("ITEM-1"), eq(1), eq("1001"));
        }
    }

    // -----------------------------------------------------------------------
    // Inventory Reservation Failure Scenarios
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Inventory Reservation Failures")
    class InventoryFailureScenarios {

        @Test
        @DisplayName("First item fails → no compensation needed, order FAILED")
        void executeOrderSaga_firstItemFails_noCompensation() {
            // Given: first decrement returns false (insufficient stock)
            Order order = createTestOrder(3);
            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenReturn(false);

            // When
            Order result = orchestrator.executeOrderSaga(order);

            // Then: order status is FAILED
            assertThat(result.getStatus()).isEqualTo("FAILED");

            // Only 1 decrement call was attempted (failed on first item, then breaks)
            verify(catalogServiceClient, times(1))
                    .decrementInventory(anyString(), anyInt(), anyString());

            // No compensation needed since no items were actually decremented
            // (inventoryCompensation.compensate() NOT called because decrementedItems is empty)
            verify(inventoryCompensation, never()).compensate(any(Order.class), anyList());
        }

        @Test
        @DisplayName("Partial failure → compensation for already-decremented items only")
        void executeOrderSaga_partialFailure_compensatesDecrementedItemsOnly() {
            // Given: items 1 and 2 succeed, item 3 fails
            Order order = createTestOrder(3);
            when(catalogServiceClient.decrementInventory(eq("ITEM-1"), anyInt(), anyString()))
                    .thenReturn(true);
            when(catalogServiceClient.decrementInventory(eq("ITEM-2"), anyInt(), anyString()))
                    .thenReturn(true);
            when(catalogServiceClient.decrementInventory(eq("ITEM-3"), anyInt(), anyString()))
                    .thenReturn(false);

            when(inventoryCompensation.compensate(any(Order.class), anyList()))
                    .thenReturn(new InventoryCompensation.CompensationResult(
                            List.of("ITEM-1", "ITEM-2"), Collections.emptyList()));

            // When
            Order result = orchestrator.executeOrderSaga(order);

            // Then: order is FAILED
            assertThat(result.getStatus()).isEqualTo("FAILED");

            // Verify exactly 3 decrement calls (1 and 2 succeed, 3 fails, then stops)
            verify(catalogServiceClient, times(3))
                    .decrementInventory(anyString(), anyInt(), anyString());

            // Verify compensation called with only the 2 successfully decremented items
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LineItem>> compensatedCaptor =
                    ArgumentCaptor.forClass(List.class);
            verify(inventoryCompensation).compensate(any(Order.class), compensatedCaptor.capture());
            List<LineItem> compensatedItems = compensatedCaptor.getValue();
            assertThat(compensatedItems).hasSize(2);
            assertThat(compensatedItems.get(0).getItemId()).isEqualTo("ITEM-1");
            assertThat(compensatedItems.get(1).getItemId()).isEqualTo("ITEM-2");
        }

        @Test
        @DisplayName("All items succeed but compensation has partial failure → logged as CRITICAL")
        void executeOrderSaga_compensationPartialFailure_orderStillFailed() {
            // Given: item 1 succeeds, item 2 fails
            Order order = createTestOrder(2);
            when(catalogServiceClient.decrementInventory(eq("ITEM-1"), anyInt(), anyString()))
                    .thenReturn(true);
            when(catalogServiceClient.decrementInventory(eq("ITEM-2"), anyInt(), anyString()))
                    .thenReturn(false);

            // Compensation partially fails (ITEM-1 cannot be restored)
            when(inventoryCompensation.compensate(any(Order.class), anyList()))
                    .thenReturn(new InventoryCompensation.CompensationResult(
                            Collections.emptyList(), List.of("ITEM-1")));

            // When
            Order result = orchestrator.executeOrderSaga(order);

            // Then: order is still FAILED (compensation failure doesn't change this)
            assertThat(result.getStatus()).isEqualTo("FAILED");

            // Verify compensation was invoked
            verify(inventoryCompensation).compensate(any(Order.class), anyList());
        }
    }

    // -----------------------------------------------------------------------
    // Catalog Service Unreachable Scenarios
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Catalog Service Unreachable")
    class CatalogUnavailableScenarios {

        @Test
        @DisplayName("RuntimeException during inventory call → compensation + FAILED")
        void executeOrderSaga_catalogServiceThrows_compensatesAndFails() {
            // Given: 2 items — first succeeds, second throws RuntimeException
            Order order = createTestOrder(2);
            when(catalogServiceClient.decrementInventory(eq("ITEM-1"), anyInt(), anyString()))
                    .thenReturn(true);
            when(catalogServiceClient.decrementInventory(eq("ITEM-2"), anyInt(), anyString()))
                    .thenThrow(new RuntimeException("Connection refused: catalog-service:8082"));

            when(inventoryCompensation.compensate(any(Order.class), anyList()))
                    .thenReturn(new InventoryCompensation.CompensationResult(
                            List.of("ITEM-1"), Collections.emptyList()));

            // When
            Order result = orchestrator.executeOrderSaga(order);

            // Then: order FAILED
            assertThat(result.getStatus()).isEqualTo("FAILED");

            // Verify compensation called with the 1 decremented item
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LineItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(inventoryCompensation).compensate(any(Order.class), captor.capture());
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0).getItemId()).isEqualTo("ITEM-1");
        }

        @Test
        @DisplayName("RuntimeException on first call → no compensation, order FAILED")
        void executeOrderSaga_catalogThrowsOnFirst_noCompensation() {
            // Given: first decrement call throws immediately
            Order order = createTestOrder(2);
            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenThrow(new RuntimeException("Service unavailable"));

            // When
            Order result = orchestrator.executeOrderSaga(order);

            // Then: order FAILED, no compensation (nothing was decremented)
            assertThat(result.getStatus()).isEqualTo("FAILED");
            verify(inventoryCompensation, never()).compensate(any(Order.class), anyList());
        }
    }

    // -----------------------------------------------------------------------
    // Idempotency Key Verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Idempotency Key")
    class IdempotencyScenarios {

        @Test
        @DisplayName("orderId passed as String idempotency key to every decrement call")
        void executeOrderSaga_passesOrderIdAsStringIdempotencyKey() {
            // Given: order with 3 items
            Order order = createTestOrder(3);
            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenReturn(true);

            // When
            orchestrator.executeOrderSaga(order);

            // Then: each decrement call receives "1001" (the auto-generated orderId as String)
            verify(catalogServiceClient).decrementInventory(eq("ITEM-1"), eq(1), eq("1001"));
            verify(catalogServiceClient).decrementInventory(eq("ITEM-2"), eq(2), eq("1001"));
            verify(catalogServiceClient).decrementInventory(eq("ITEM-3"), eq(3), eq("1001"));
        }
    }

    // -----------------------------------------------------------------------
    // Saga State Machine Transitions
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Saga State Transitions")
    class StateMachineScenarios {

        @Test
        @DisplayName("Successful saga: PENDING → RESERVE_INVENTORY → INVENTORY_RESERVED → COMPLETED")
        void executeOrderSaga_success_correctStateTransitions() {
            // Given
            Order order = createTestOrder(1);
            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenReturn(true);

            // Capture saga state snapshots at each save (since the same object is
            // mutated in-place, ArgumentCaptor would show final state for all captures)
            List<String> sagaStatuses = new ArrayList<>();
            List<OrderSagaStep> sagaSteps = new ArrayList<>();
            doAnswer(invocation -> {
                OrderSagaState state = invocation.getArgument(0);
                sagaStatuses.add(state.getStatus());
                sagaSteps.add(state.getCurrentStep());
                return state;
            }).when(sagaStateRepository).save(any(OrderSagaState.class));

            // When
            orchestrator.executeOrderSaga(order);

            // Then: verify saga state transitions in order
            assertThat(sagaStatuses).hasSize(4);
            assertThat(sagaSteps).hasSize(4);

            // Initial creation: PENDING / CREATE_ORDER
            assertThat(sagaStatuses.get(0)).isEqualTo(OrderSagaState.STATUS_PENDING);
            assertThat(sagaSteps.get(0)).isEqualTo(OrderSagaStep.CREATE_ORDER);

            // Step 2 start: RESERVE_INVENTORY step (status unchanged: PENDING)
            assertThat(sagaSteps.get(1)).isEqualTo(OrderSagaStep.RESERVE_INVENTORY);

            // Step 2 complete: INVENTORY_RESERVED status
            assertThat(sagaStatuses.get(2)).isEqualTo(OrderSagaState.STATUS_INVENTORY_RESERVED);

            // Step 3 complete: COMPLETED status / CONFIRM_ORDER step
            assertThat(sagaStatuses.get(3)).isEqualTo(OrderSagaState.STATUS_COMPLETED);
            assertThat(sagaSteps.get(3)).isEqualTo(OrderSagaStep.CONFIRM_ORDER);
        }

        @Test
        @DisplayName("Failed saga: PENDING → RESERVE_INVENTORY → COMPENSATING → FAILED")
        void executeOrderSaga_failure_correctStateTransitions() {
            // Given: item fails immediately
            Order order = createTestOrder(1);
            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenReturn(false);

            // Capture saga state snapshots at each save
            List<String> sagaStatuses = new ArrayList<>();
            List<OrderSagaStep> sagaSteps = new ArrayList<>();
            doAnswer(invocation -> {
                OrderSagaState state = invocation.getArgument(0);
                sagaStatuses.add(state.getStatus());
                sagaSteps.add(state.getCurrentStep());
                return state;
            }).when(sagaStateRepository).save(any(OrderSagaState.class));

            // When
            orchestrator.executeOrderSaga(order);

            // Then: verify saga state transitions
            assertThat(sagaStatuses).hasSize(4);
            assertThat(sagaSteps).hasSize(4);

            // Initial creation: PENDING / CREATE_ORDER
            assertThat(sagaStatuses.get(0)).isEqualTo(OrderSagaState.STATUS_PENDING);
            assertThat(sagaSteps.get(0)).isEqualTo(OrderSagaStep.CREATE_ORDER);

            // Step 2 start: RESERVE_INVENTORY step
            assertThat(sagaSteps.get(1)).isEqualTo(OrderSagaStep.RESERVE_INVENTORY);

            // Compensation triggered: COMPENSATING status
            assertThat(sagaStatuses.get(2)).isEqualTo(OrderSagaState.STATUS_COMPENSATING);

            // Terminal: FAILED status, currentStep = RESERVE_INVENTORY (where failure occurred)
            assertThat(sagaStatuses.get(3)).isEqualTo(OrderSagaState.STATUS_FAILED);
            assertThat(sagaSteps.get(3)).isEqualTo(OrderSagaStep.RESERVE_INVENTORY);
        }
    }

    // -----------------------------------------------------------------------
    // Transaction Boundary Verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Transaction Boundaries (AAP §0.7.1)")
    class TransactionBoundaryScenarios {

        @Test
        @DisplayName("Step 1 commits independently before Step 2 begins")
        void executeOrderSaga_step1CommitsBeforeStep2() {
            // Given: catalog client records when it's called and checks commitCount
            Order order = createTestOrder(1);
            final int[] commitCountAtStep2 = new int[1];

            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        // When Step 2 executes, Step 1's transaction should already be committed
                        commitCountAtStep2[0] = commitCount;
                        return true;
                    });

            // When
            orchestrator.executeOrderSaga(order);

            // Then: at least 1 commit occurred before Step 2 started (Step 1's independent tx)
            assertThat(commitCountAtStep2[0]).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Multiple independent commits occur during successful saga")
        void executeOrderSaga_success_multipleIndependentCommits() {
            // Given
            Order order = createTestOrder(1);
            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenReturn(true);

            // When
            orchestrator.executeOrderSaga(order);

            // Then: multiple commits occurred (Step 1, saga state updates, Step 3)
            // Step 1 (create order) + saga step update + inventory reserved update + Step 3 (confirm)
            assertThat(commitCount).isGreaterThanOrEqualTo(3);
        }
    }

    // -----------------------------------------------------------------------
    // OrderStatus Quirk Verification
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Monolith Quirk Replication")
    class QuirkScenarios {

        @Test
        @DisplayName("OrderStatus.lineNum equals orderId (monolith quirk)")
        void executeOrderSaga_orderStatusLineNumEqualsOrderId() {
            // Given
            Order order = createTestOrder(1);
            when(catalogServiceClient.decrementInventory(anyString(), anyInt(), anyString()))
                    .thenReturn(true);

            // When
            orchestrator.executeOrderSaga(order);

            // Then: verify OrderStatus was saved with lineNum == orderId
            ArgumentCaptor<OrderStatus> statusCaptor = ArgumentCaptor.forClass(OrderStatus.class);
            verify(orderStatusRepository).save(statusCaptor.capture());
            OrderStatus savedStatus = statusCaptor.getValue();
            assertThat(savedStatus.getOrderId()).isEqualTo(1001);
            assertThat(savedStatus.getLineNum()).isEqualTo(1001); // QUIRK: lineNum = orderId
            assertThat(savedStatus.getStatus()).isEqualTo("P");
        }
    }
}
