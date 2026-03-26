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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;
import com.jpetstore.order.saga.InventoryCompensation.CompensationResult;

/**
 * Unit test for {@link InventoryCompensation} — the Saga compensating transaction
 * handler that restores inventory via the Catalog Service REST API when order
 * placement fails after partial or full inventory decrement.
 *
 * <p>Follows the exact testing patterns from the monolith's {@code OrderServiceTest}
 * and {@code AccountServiceTest}: pure unit test with {@code @ExtendWith(MockitoExtension.class)},
 * no Spring context, Given-When-Then structure, AssertJ assertions, and Mockito
 * verify/when stubbing.</p>
 *
 * <p>Covers 6 scenarios for complete path coverage of
 * {@link InventoryCompensation#compensate(Order, List)}:</p>
 * <ol>
 *   <li>All items restored successfully (happy path)</li>
 *   <li>Partial compensation failure (some items fail)</li>
 *   <li>All compensations fail (complete failure)</li>
 *   <li>Empty decremented items list (edge case)</li>
 *   <li>OrderId passed as String for idempotency/traceability</li>
 *   <li>Single item compensation</li>
 * </ol>
 *
 * @author Blitzy Platform
 * @see InventoryCompensation
 * @see CatalogServiceClient#restoreInventory(String, int, String)
 */
@ExtendWith(MockitoExtension.class)
class InventoryCompensationTest {

    @Mock
    private CatalogServiceClient catalogServiceClient;

    @InjectMocks
    private InventoryCompensation inventoryCompensation;

    // -----------------------------------------------------------------------
    // Helper methods for test entity construction
    // -----------------------------------------------------------------------

    /**
     * Creates an {@link Order} JPA entity with the specified orderId.
     * Uses no-arg constructor + setter, matching JPA entity construction pattern.
     *
     * @param orderId the order identifier to set
     * @return a new Order entity with the given orderId
     */
    private Order createOrder(int orderId) {
        Order order = new Order();
        order.setOrderId(orderId);
        return order;
    }

    /**
     * Creates a {@link LineItem} JPA entity with the specified itemId and quantity.
     * Uses no-arg constructor + setters, matching JPA entity construction pattern.
     *
     * @param itemId   the catalog item identifier (e.g., "EST-1")
     * @param quantity the number of units that were decremented
     * @return a new LineItem entity with the given itemId and quantity
     */
    private LineItem createLineItem(String itemId, int quantity) {
        LineItem lineItem = new LineItem();
        lineItem.setItemId(itemId);
        lineItem.setQuantity(quantity);
        return lineItem;
    }

    // -----------------------------------------------------------------------
    // Test 1: All items restored successfully — happy path
    // -----------------------------------------------------------------------

    /**
     * Verifies that when all line items are successfully restored via the Catalog
     * Service REST API, the {@link CompensationResult} reports full compensation
     * with no failures.
     *
     * <p>This is the happy path for the Saga compensating transaction: every
     * {@code restoreInventory()} call returns {@code true}, indicating the Catalog
     * Service successfully incremented inventory for each previously decremented item.</p>
     */
    @Test
    void shouldRestoreAllItemsSuccessfully() {
        // given
        Order order = createOrder(100);
        LineItem lineItem1 = createLineItem("EST-1", 2);
        LineItem lineItem2 = createLineItem("EST-2", 3);
        LineItem lineItem3 = createLineItem("EST-3", 1);

        when(catalogServiceClient.restoreInventory("EST-1", 2, "100")).thenReturn(true);
        when(catalogServiceClient.restoreInventory("EST-2", 3, "100")).thenReturn(true);
        when(catalogServiceClient.restoreInventory("EST-3", 1, "100")).thenReturn(true);

        // when
        CompensationResult result = inventoryCompensation.compensate(order,
                List.of(lineItem1, lineItem2, lineItem3));

        // then
        assertThat(result.isFullyCompensated()).isTrue();
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.restoredItemIds()).containsExactly("EST-1", "EST-2", "EST-3");
        assertThat(result.failedItemIds()).isEmpty();

        verify(catalogServiceClient, times(3)).restoreInventory(anyString(), anyInt(), anyString());
        verify(catalogServiceClient).restoreInventory(eq("EST-1"), eq(2), eq("100"));
        verify(catalogServiceClient).restoreInventory(eq("EST-2"), eq(3), eq("100"));
        verify(catalogServiceClient).restoreInventory(eq("EST-3"), eq(1), eq("100"));
    }

    // -----------------------------------------------------------------------
    // Test 2: Partial compensation failure
    // -----------------------------------------------------------------------

    /**
     * Verifies that when some restore calls succeed and others fail, the
     * {@link CompensationResult} correctly tracks which items were restored
     * and which failed. Critically, compensation MUST attempt ALL items even
     * if some fail — it does not short-circuit on the first failure.
     *
     * <p>This scenario occurs when the Catalog Service is intermittently
     * available or specific item restoration encounters an error while
     * others succeed.</p>
     */
    @Test
    void shouldHandlePartialCompensationFailure() {
        // given
        Order order = createOrder(200);
        LineItem lineItem1 = createLineItem("EST-1", 2);
        LineItem lineItem2 = createLineItem("EST-2", 3);
        LineItem lineItem3 = createLineItem("EST-3", 1);

        when(catalogServiceClient.restoreInventory("EST-1", 2, "200")).thenReturn(true);
        when(catalogServiceClient.restoreInventory("EST-2", 3, "200")).thenReturn(false);
        when(catalogServiceClient.restoreInventory("EST-3", 1, "200")).thenReturn(true);

        // when
        CompensationResult result = inventoryCompensation.compensate(order,
                List.of(lineItem1, lineItem2, lineItem3));

        // then
        assertThat(result.isFullyCompensated()).isFalse();
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.restoredItemIds()).containsExactlyInAnyOrder("EST-1", "EST-3");
        assertThat(result.failedItemIds()).containsExactly("EST-2");

        // Verify all 3 calls were made — compensation must not short-circuit
        verify(catalogServiceClient, times(3)).restoreInventory(anyString(), anyInt(), anyString());
        verify(catalogServiceClient).restoreInventory(eq("EST-1"), eq(2), eq("200"));
        verify(catalogServiceClient).restoreInventory(eq("EST-2"), eq(3), eq("200"));
        verify(catalogServiceClient).restoreInventory(eq("EST-3"), eq(1), eq("200"));
    }

    // -----------------------------------------------------------------------
    // Test 3: All compensations fail
    // -----------------------------------------------------------------------

    /**
     * Verifies that when every restore call fails, the {@link CompensationResult}
     * reports complete failure with all item IDs in the failed list. This is the
     * worst-case scenario requiring manual intervention — every ERROR-level log
     * entry with "CRITICAL" prefix would be emitted for operational alerting.
     */
    @Test
    void shouldHandleAllCompensationsFailing() {
        // given
        Order order = createOrder(300);
        LineItem lineItem1 = createLineItem("EST-1", 2);
        LineItem lineItem2 = createLineItem("EST-2", 3);

        when(catalogServiceClient.restoreInventory("EST-1", 2, "300")).thenReturn(false);
        when(catalogServiceClient.restoreInventory("EST-2", 3, "300")).thenReturn(false);

        // when
        CompensationResult result = inventoryCompensation.compensate(order,
                List.of(lineItem1, lineItem2));

        // then
        assertThat(result.isFullyCompensated()).isFalse();
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.restoredItemIds()).isEmpty();
        assertThat(result.failedItemIds()).containsExactlyInAnyOrder("EST-1", "EST-2");

        // Verify both calls were attempted despite failures
        verify(catalogServiceClient, times(2)).restoreInventory(anyString(), anyInt(), anyString());
        verify(catalogServiceClient).restoreInventory(eq("EST-1"), eq(2), eq("300"));
        verify(catalogServiceClient).restoreInventory(eq("EST-2"), eq(3), eq("300"));
    }

    // -----------------------------------------------------------------------
    // Test 4: Empty decremented items list — edge case
    // -----------------------------------------------------------------------

    /**
     * Verifies the edge case where no inventory was decremented before the order
     * failure occurred. The compensation should report success (no failures)
     * without making any REST calls to the Catalog Service.
     *
     * <p>This scenario occurs when the Saga fails at Step 1 (create order locally)
     * before any inventory reservation calls were made in Step 2.</p>
     */
    @Test
    void shouldHandleEmptyDecrementedItemsList() {
        // given
        Order order = createOrder(400);

        // when
        CompensationResult result = inventoryCompensation.compensate(order, Collections.emptyList());

        // then
        assertThat(result.isFullyCompensated()).isTrue();
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.restoredItemIds()).isEmpty();
        assertThat(result.failedItemIds()).isEmpty();

        // Verify zero invocations — no REST calls should be made for empty list
        verify(catalogServiceClient, never()).restoreInventory(anyString(), anyInt(), anyString());
    }

    // -----------------------------------------------------------------------
    // Test 5: OrderId passed as String for traceability/idempotency
    // -----------------------------------------------------------------------

    /**
     * Verifies that the orderId is correctly converted to a String and passed
     * as the idempotency key in every {@code restoreInventory()} call.
     *
     * <p>Per AAP Section 0.7.1, every inventory reservation/restoration request
     * includes the orderId as an idempotency key. The Catalog Service stores
     * reservation records indexed by orderId and returns success for duplicate
     * requests without double-restoring inventory. This test validates that
     * {@code String.valueOf(order.getOrderId())} is correctly used.</p>
     *
     * <p>This is CRITICAL because using the wrong idempotency key (e.g., int
     * instead of String, or a missing orderId) would break the Catalog Service's
     * idempotency guarantee and risk double-restoration of inventory.</p>
     */
    @Test
    void shouldPassOrderIdAsStringForTraceability() {
        // given
        Order order = createOrder(500);
        LineItem lineItem = createLineItem("EST-10", 5);

        when(catalogServiceClient.restoreInventory("EST-10", 5, "500")).thenReturn(true);

        // when
        inventoryCompensation.compensate(order, List.of(lineItem));

        // then — verify exact argument matching including orderId as String "500"
        verify(catalogServiceClient).restoreInventory(eq("EST-10"), eq(5), eq("500"));
    }

    // -----------------------------------------------------------------------
    // Test 6: Single item compensation
    // -----------------------------------------------------------------------

    /**
     * Verifies the scenario where only one item was decremented before the order
     * failure. This is the simplest non-empty compensation case.
     *
     * <p>This scenario occurs when the first inventory decrement succeeds but the
     * second one fails (or a subsequent saga step fails), and only one item needs
     * to be compensated.</p>
     */
    @Test
    void shouldHandleSingleItemCompensation() {
        // given
        Order order = createOrder(600);
        LineItem lineItem = createLineItem("EST-5", 10);

        when(catalogServiceClient.restoreInventory("EST-5", 10, "600")).thenReturn(true);

        // when
        CompensationResult result = inventoryCompensation.compensate(order, List.of(lineItem));

        // then
        assertThat(result.isFullyCompensated()).isTrue();
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.restoredItemIds()).containsExactly("EST-5");
        assertThat(result.failedItemIds()).isEmpty();

        // Verify exactly one restore call was made
        verify(catalogServiceClient, times(1)).restoreInventory(anyString(), anyInt(), anyString());
        verify(catalogServiceClient).restoreInventory(eq("EST-5"), eq(10), eq("600"));
    }
}
