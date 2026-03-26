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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jpetstore.catalog.entity.Inventory;
import com.jpetstore.catalog.entity.InventoryReservation;
import com.jpetstore.catalog.repository.InventoryRepository;
import com.jpetstore.catalog.repository.InventoryReservationRepository;

/**
 * Unit tests for {@link InventoryService}.
 *
 * <p>This test class verifies the inventory management operations that were extracted from the
 * monolith's {@code OrderService.insertOrder()} method (where {@code itemMapper.updateInventoryQuantity()}
 * was called inline) into a dedicated {@code InventoryService} within the Catalog Service
 * bounded context.
 *
 * <p>Follows the exact testing patterns established in the monolith's {@code CatalogServiceTest}:
 * <ul>
 *   <li>{@code @ExtendWith(MockitoExtension.class)} for Mockito-managed lifecycle</li>
 *   <li>{@code @Mock} for repository dependencies (replacing monolith's mapper mocks)</li>
 *   <li>{@code @InjectMocks} for service under test</li>
 *   <li>AssertJ {@code assertThat} for fluent assertions</li>
 *   <li>Given / when / then structure in every test</li>
 *   <li>Pure unit tests with NO Spring context loaded</li>
 * </ul>
 *
 * <h3>Test Coverage</h3>
 * <ul>
 *   <li>{@link #shouldDecrementInventorySuccessfully()} — Saga decrement success path</li>
 *   <li>{@link #shouldReturnFalseWhenInsufficientStock()} — Saga decrement failure path</li>
 *   <li>{@link #shouldRestoreInventorySuccessfully()} — Saga compensation success path</li>
 *   <li>{@link #shouldThrowWhenRestoringNonExistentInventory()} — Saga compensation error path</li>
 *   <li>{@link #shouldReturnInventoryQuantity()} — Inventory read success path</li>
 *   <li>{@link #shouldReturnZeroWhenInventoryNotFound()} — Inventory read missing-data path</li>
 * </ul>
 *
 * @see InventoryService
 * @see InventoryRepository
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private InventoryReservationRepository reservationRepository;

    @InjectMocks
    private InventoryService inventoryService;

    /**
     * Verifies that {@link InventoryService#decrementInventory(String, int, String)} returns
     * {@code true} when the repository reports 1 row affected (sufficient stock available).
     *
     * <p>This tests the critical Saga step success path (AAP Section 0.7.1) where the
     * Order Service's Saga orchestrator calls {@code POST /api/items/{id}/inventory/decrement}
     * and the inventory decrement succeeds.
     *
     * <p>Mirrors the monolith behavior of {@code itemMapper.updateInventoryQuantity(param)}
     * within {@code OrderService.insertOrder()} (lines 62-68), where a successful update
     * meant sufficient stock was available.
     */
    @Test
    void shouldDecrementInventorySuccessfully() {
        // given
        String itemId = "EST-1";
        int quantity = 2;
        String orderId = "ORD-001";

        // when — no prior reservation exists (first attempt)
        when(reservationRepository.findByOrderIdAndItemId(orderId, itemId))
                .thenReturn(Optional.empty());
        when(inventoryRepository.decrementQuantity(itemId, quantity)).thenReturn(1);
        boolean result = inventoryService.decrementInventory(itemId, quantity, orderId);

        // then
        assertThat(result).isTrue();
        verify(inventoryRepository).decrementQuantity(itemId, quantity);
        verify(reservationRepository).save(ArgumentMatchers.any(InventoryReservation.class));
    }

    /**
     * Verifies that {@link InventoryService#decrementInventory(String, int, String)} returns
     * {@code false} when the repository reports 0 rows affected (insufficient stock or item
     * not found).
     *
     * <p>This tests the critical Saga step failure path (AAP Section 0.7.1). When the
     * {@code decrementQuantity} query's {@code qty >= :decrement} guard is not satisfied,
     * 0 rows are updated, and the service returns {@code false}. The Order Service's Saga
     * orchestrator should then trigger compensation for any previously decremented items.
     *
     * <p>Note: The monolith's original {@code ItemMapper.xml} did NOT have the
     * {@code qty >= :decrement} guard — the new implementation adds this safety improvement.
     */
    @Test
    void shouldReturnFalseWhenInsufficientStock() {
        // given
        String itemId = "EST-1";
        int quantity = 100;
        String orderId = "ORD-002";

        // when — no prior reservation exists
        when(reservationRepository.findByOrderIdAndItemId(orderId, itemId))
                .thenReturn(Optional.empty());
        when(inventoryRepository.decrementQuantity(itemId, quantity)).thenReturn(0);
        boolean result = inventoryService.decrementInventory(itemId, quantity, orderId);

        // then
        assertThat(result).isFalse();
        verify(inventoryRepository).decrementQuantity(itemId, quantity);
        verify(reservationRepository, never()).save(ArgumentMatchers.any(InventoryReservation.class));
    }

    /**
     * Verifies that {@link InventoryService#restoreInventory(String, int, String)} correctly
     * adds the specified quantity back to the existing inventory record.
     *
     * <p>This tests the Saga compensation success path (AAP Section 0.7.1). When order
     * placement fails after inventory was already decremented, the {@code restoreInventory}
     * method is called as the compensating transaction to reverse the decrement.
     *
     * <p>The test creates an {@link Inventory} entity with initial qty=5, calls
     * {@code restoreInventory} with qty=2, and verifies the entity's quantity is updated
     * to 7 (5+2) and that {@code save()} is called on the repository.
     */
    @Test
    void shouldRestoreInventorySuccessfully() {
        // given
        String itemId = "EST-1";
        int quantity = 2;
        String orderId = "ORD-003";
        Inventory inventory = new Inventory();
        inventory.setItemId(itemId);
        inventory.setQty(5);
        InventoryReservation reservation = new InventoryReservation(orderId, itemId, quantity);

        // when
        when(inventoryRepository.findById(itemId)).thenReturn(Optional.of(inventory));
        when(reservationRepository.findByOrderIdAndItemId(orderId, itemId))
                .thenReturn(Optional.of(reservation));
        inventoryService.restoreInventory(itemId, quantity, orderId);

        // then
        assertThat(inventory.getQty()).isEqualTo(7); // 5 + 2 = 7
        verify(inventoryRepository).save(inventory);
        verify(reservationRepository).delete(reservation);
    }

    /**
     * Verifies that {@link InventoryService#restoreInventory(String, int, String)} throws
     * an {@link IllegalStateException} when the inventory record does not exist.
     *
     * <p>This scenario should never occur in normal Saga operation (a restore is only triggered
     * after a successful decrement, meaning the inventory record must exist). However, it is
     * tested as a defensive guard against data inconsistency. The test also verifies that
     * {@code save()} is never called when the exception is thrown.
     */
    @Test
    void shouldThrowWhenRestoringNonExistentInventory() {
        // given
        String itemId = "NONEXISTENT";
        int quantity = 2;
        String orderId = "ORD-004";

        // when — mock a valid reservation so the code passes the reservation check
        // and reaches the inventory existence check where IllegalStateException is thrown
        InventoryReservation reservation = new InventoryReservation(orderId, itemId, quantity);
        when(reservationRepository.findByOrderIdAndItemId(orderId, itemId))
                .thenReturn(Optional.of(reservation));
        when(inventoryRepository.findById(itemId)).thenReturn(Optional.empty());

        // then
        assertThatThrownBy(() -> inventoryService.restoreInventory(itemId, quantity, orderId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(itemId);
        verify(inventoryRepository, never()).save(ArgumentMatchers.any());
    }

    /**
     * Verifies that {@link InventoryService#getInventoryQuantity(String)} returns the correct
     * quantity when the inventory record exists.
     *
     * <p>This tests the read path that corresponds to the monolith's
     * {@code ItemMapper.getInventoryQuantity(String)} method, which executed:
     * <pre>
     * SELECT QTY AS value FROM INVENTORY WHERE ITEMID = #{itemId}
     * </pre>
     *
     * <p>In the decomposed architecture, this is implemented via
     * {@code inventoryRepository.findById(itemId).map(Inventory::getQty).orElse(0)}.
     */
    @Test
    void shouldReturnInventoryQuantity() {
        // given
        String itemId = "EST-1";
        Inventory inventory = new Inventory();
        inventory.setItemId(itemId);
        inventory.setQty(10);

        // when
        when(inventoryRepository.findById(itemId)).thenReturn(Optional.of(inventory));
        int result = inventoryService.getInventoryQuantity(itemId);

        // then
        assertThat(result).isEqualTo(10);
    }

    /**
     * Verifies that {@link InventoryService#getInventoryQuantity(String)} returns 0 when
     * the inventory record does not exist.
     *
     * <p>This matches the monolith's behavior where a missing inventory record effectively
     * means zero stock. In the monolith, the MyBatis query returned {@code null} for missing
     * items, which the caller interpreted as zero. The new implementation uses
     * {@code Optional.orElse(0)} for the same semantics.
     */
    @Test
    void shouldReturnZeroWhenInventoryNotFound() {
        // given
        String itemId = "NONEXISTENT";

        // when
        when(inventoryRepository.findById(itemId)).thenReturn(Optional.empty());
        int result = inventoryService.getInventoryQuantity(itemId);

        // then
        assertThat(result).isEqualTo(0);
    }

}
