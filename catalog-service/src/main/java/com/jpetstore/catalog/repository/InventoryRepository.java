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

import com.jpetstore.catalog.entity.Inventory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for the {@link Inventory} entity.
 *
 * <p>This repository replaces the inventory-related methods from the monolith's
 * {@code ItemMapper} interface ({@code getInventoryQuantity} and {@code updateInventoryQuantity}).
 * It is the most critical repository in the Catalog Service because it handles the atomic
 * inventory decrement operation that is called by the Order Service's Saga orchestrator
 * during order placement.
 *
 * <h3>Method Mapping from Monolith ItemMapper</h3>
 * <table>
 *   <tr><th>Original MyBatis Method</th><th>Repository Equivalent</th></tr>
 *   <tr>
 *     <td>{@code int getInventoryQuantity(String itemId)}</td>
 *     <td>{@link #findById(String)} — returns {@code Optional<Inventory>};
 *         caller extracts qty via {@code .map(Inventory::getQty).orElse(0)}</td>
 *   </tr>
 *   <tr>
 *     <td>{@code void updateInventoryQuantity(Map<String, Object> param)}</td>
 *     <td>{@link #decrementQuantity(String, int)} — atomic decrement with oversell guard</td>
 *   </tr>
 * </table>
 *
 * <h3>Concurrency and Safety</h3>
 * <p>The custom {@link #decrementQuantity(String, int)} method includes a safety guard
 * ({@code qty >= :decrement}) that the original MyBatis SQL lacked. This prevents inventory
 * from going negative, returning 0 rows affected when insufficient stock exists, which
 * allows the {@code InventoryService} to detect and handle the failure gracefully.
 *
 * <p>For entity-based operations via {@link #save(Inventory)}, the {@link Inventory} entity's
 * {@code @Version} field provides optimistic locking, throwing
 * {@link org.springframework.orm.ObjectOptimisticLockingFailureException} on concurrent
 * modification. This is useful for Saga compensation (inventory restore) operations where
 * the entity is loaded, modified, and saved back.
 *
 * <p>Note: The {@link #decrementQuantity(String, int)} method operates as a bulk JPQL UPDATE
 * and does <strong>not</strong> trigger the {@code @Version} check automatically. The
 * {@code qty >= :decrement} WHERE clause provides the application-level safety for this path.
 *
 * <p>No {@code @Repository} annotation is needed — Spring Boot auto-detects interfaces
 * extending {@link JpaRepository} during component scanning.
 *
 * @see Inventory
 * @see com.jpetstore.catalog.service.InventoryService
 */
public interface InventoryRepository extends JpaRepository<Inventory, String> {

    /**
     * Atomically decrements the inventory quantity for the specified item.
     *
     * <p>This method replaces the monolith's MyBatis {@code updateInventoryQuantity} operation:
     * <pre>
     * -- Original MyBatis SQL (ItemMapper.xml):
     * UPDATE INVENTORY SET QTY = QTY - #{increment} WHERE ITEMID = #{itemId}
     * </pre>
     *
     * <p>The JPQL includes an additional safety guard ({@code i.qty >= :decrement}) that prevents
     * overselling by refusing to decrement when insufficient stock exists. The original MyBatis
     * SQL did not have this guard and could drive inventory negative.
     *
     * <h4>Return Value Semantics</h4>
     * <ul>
     *   <li>{@code 1} — decrement succeeded; the item had sufficient stock and quantity was reduced</li>
     *   <li>{@code 0} — decrement failed; either the item does not exist in the inventory table,
     *       or the current quantity is less than the requested decrement amount</li>
     * </ul>
     *
     * <h4>Usage by Saga Orchestrator</h4>
     * <p>The Order Service's {@code OrderSagaOrchestrator} calls the Catalog Service's
     * {@code POST /api/items/{id}/inventory/decrement} endpoint for each line item in an order.
     * The {@code InventoryService} delegates to this method and checks the return value:
     * <ul>
     *   <li>If {@code 1}: inventory reserved successfully; proceed to next line item</li>
     *   <li>If {@code 0}: insufficient stock; trigger Saga compensation to restore
     *       previously decremented items</li>
     * </ul>
     *
     * <h4>Transaction Management</h4>
     * <p>This method does not declare its own {@code @Transactional} boundary. Transaction
     * management is the responsibility of the calling service layer
     * ({@code InventoryService.decrementQuantity()}), which wraps this call in a
     * {@code @Transactional} context.
     *
     * @param itemId    the item identifier whose inventory should be decremented;
     *                  must match an existing {@code itemid} in the inventory table
     * @param decrement the quantity to subtract from the current stock; must be a positive integer
     * @return the number of rows affected: {@code 1} if the decrement was applied,
     *         {@code 0} if the item was not found or had insufficient stock
     */
    @Modifying
    @Query("UPDATE Inventory i SET i.qty = i.qty - :decrement "
            + "WHERE i.itemId = :itemId AND i.qty >= :decrement")
    int decrementQuantity(@Param("itemId") String itemId, @Param("decrement") int decrement);

}
