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

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;

/**
 * Saga compensating transaction handler for inventory reservation rollback.
 *
 * <p>In the monolith, {@code OrderService.insertOrder()} executes all inventory
 * decrements and order writes within a single {@code @Transactional} boundary.
 * If any step fails, the entire transaction rolls back automatically via ACID
 * guarantees — no explicit compensation is needed.</p>
 *
 * <p>After decomposition, the inventory table is owned by the Catalog Service
 * and accessed via REST calls. When an order placement fails <b>after</b> some
 * inventory has been successfully decremented, automatic rollback is impossible
 * across the distributed system boundary. This class provides the explicit
 * compensating transaction — calling the Catalog Service's
 * {@code POST /api/items/{id}/inventory/restore} endpoint for each item that
 * was successfully decremented before the failure occurred.</p>
 *
 * <h3>Saga Pattern Context (AAP §0.7.1)</h3>
 * <p>This class participates in the orchestration-based Saga pattern as the
 * compensating action for Saga Step 2 (inventory reservation). The
 * {@link com.jpetstore.order.saga.OrderSagaOrchestrator} invokes
 * {@link #compensate(Order, List)} when:</p>
 * <ul>
 *   <li>Multiple line items are being processed, some inventory decrements
 *       succeed, then a subsequent decrement fails (partial reservation)</li>
 *   <li>An indeterminate failure (5xx/timeout) occurs during inventory
 *       reservation and the orchestrator decides to compensate</li>
 * </ul>
 *
 * <h3>Idempotency Guarantee</h3>
 * <p>Each {@code restoreInventory()} call includes the {@code orderId} as an
 * idempotency key. The Catalog Service ensures restoration is applied exactly
 * once per orderId+itemId combination, making this compensation method safe
 * to retry without risk of double-restoring inventory.</p>
 *
 * <h3>Error Handling Philosophy</h3>
 * <p>This class <b>never throws exceptions</b>. Compensation is best-effort:
 * if a restoration call fails, the failure is logged at ERROR level with a
 * "CRITICAL" prefix indicating manual intervention is required. The structured
 * {@link CompensationResult} tracks which items were successfully restored and
 * which failed, enabling the caller to make operational decisions.</p>
 *
 * <h3>Cross-Service Data Access Rule</h3>
 * <p>Per AAP §0.8.1: "No service may access another service's database directly."
 * All inventory restoration operations go through REST calls to the Catalog Service
 * via the injected {@link CatalogServiceClient}.</p>
 *
 * @author Blitzy Platform
 * @see CatalogServiceClient#restoreInventory(String, int, String)
 * @see com.jpetstore.order.saga.OrderSagaOrchestrator
 * @see CompensationResult
 */
@Component
public class InventoryCompensation {

    private static final Logger log = LoggerFactory.getLogger(InventoryCompensation.class);

    /**
     * REST client for the Catalog Service, used to call
     * {@code POST /api/items/{id}/inventory/restore} for each item
     * that needs inventory restoration during compensation.
     */
    private final CatalogServiceClient catalogServiceClient;

    /**
     * Constructs a new {@code InventoryCompensation} with the specified
     * Catalog Service client.
     *
     * <p>Uses constructor-based dependency injection as recommended by Spring
     * best practices. The {@link CatalogServiceClient} bean is auto-detected
     * by Spring Boot's component scan and injected here.</p>
     *
     * @param catalogServiceClient the REST client for communicating with
     *                             the Catalog Service's inventory endpoints
     */
    public InventoryCompensation(CatalogServiceClient catalogServiceClient) {
        this.catalogServiceClient = catalogServiceClient;
    }

    /**
     * Executes the compensating transaction by restoring inventory for each
     * previously decremented line item.
     *
     * <p>Called by {@code OrderSagaOrchestrator} when a saga step fails after
     * inventory has been partially or fully reserved. This method iterates over
     * each line item that was <b>successfully</b> decremented and calls the
     * Catalog Service's restore endpoint to undo the inventory decrement.</p>
     *
     * <h4>Important Parameter Distinction</h4>
     * <p>The {@code decrementedItems} parameter contains <b>only</b> the subset
     * of line items whose inventory was successfully decremented before the
     * failure. This is <b>NOT</b> the same as {@code order.getLineItems()} —
     * items that were not yet processed or that failed to decrement are excluded.</p>
     *
     * <h4>Idempotency</h4>
     * <p>The order ID ({@code String.valueOf(order.getOrderId())}) is passed as
     * the idempotency key to each restoration call. The Catalog Service ensures
     * that restoration is applied exactly once per orderId+itemId combination,
     * making this method safe to call multiple times for the same order.</p>
     *
     * <h4>Logging Levels</h4>
     * <ul>
     *   <li><b>WARN</b>: Compensation start and completion (operational awareness)</li>
     *   <li><b>INFO</b>: Each successful inventory restoration</li>
     *   <li><b>ERROR</b>: Each failed inventory restoration with "CRITICAL" prefix
     *       indicating manual intervention is required</li>
     * </ul>
     *
     * @param order            the JPA entity containing the order ID used as
     *                         the idempotency key for restoration calls
     * @param decrementedItems the subset of line items whose inventory was
     *                         successfully decremented and needs to be restored;
     *                         must not be null (may be empty)
     * @return a {@link CompensationResult} tracking which items were successfully
     *         restored and which failed, enabling the caller to decide on
     *         operational alerts or manual intervention
     */
    public CompensationResult compensate(Order order, List<LineItem> decrementedItems) {
        log.warn("Starting inventory compensation for orderId: {}, items to restore: {}",
                order.getOrderId(), decrementedItems.size());

        List<String> restoredItemIds = new ArrayList<>();
        List<String> failedItemIds = new ArrayList<>();

        for (LineItem item : decrementedItems) {
            try {
                boolean success = catalogServiceClient.restoreInventory(
                        item.getItemId(),
                        item.getQuantity(),
                        String.valueOf(order.getOrderId())
                );

                if (success) {
                    restoredItemIds.add(item.getItemId());
                    log.info("Inventory restored for item: {}, quantity: {}, orderId: {}",
                            item.getItemId(), item.getQuantity(), order.getOrderId());
                } else {
                    failedItemIds.add(item.getItemId());
                    log.error("CRITICAL: Failed to restore inventory for item: {}, quantity: {}, "
                                    + "orderId: {}. Manual intervention required.",
                            item.getItemId(), item.getQuantity(), order.getOrderId());
                }
            } catch (Exception e) {
                // Compensation must never throw — catch any unexpected exception
                // and treat it as a failed restoration requiring manual intervention
                failedItemIds.add(item.getItemId());
                log.error("CRITICAL: Unexpected error during inventory restoration for item: {}, "
                                + "quantity: {}, orderId: {}. Manual intervention required.",
                        item.getItemId(), item.getQuantity(), order.getOrderId(), e);
            }
        }

        CompensationResult result = new CompensationResult(restoredItemIds, failedItemIds);

        log.warn("Compensation completed for orderId: {}. Restored: {}, Failed: {}",
                order.getOrderId(), restoredItemIds.size(), failedItemIds.size());

        return result;
    }

    /**
     * Structured result of an inventory compensation operation.
     *
     * <p>This Java 17 record provides an immutable snapshot of the compensation
     * outcome, tracking which items were successfully restored and which failed.
     * The {@code OrderSagaOrchestrator} inspects this result to determine whether
     * manual intervention is needed.</p>
     *
     * <h4>Usage Example</h4>
     * <pre>{@code
     * CompensationResult result = inventoryCompensation.compensate(order, decrementedItems);
     * if (result.hasFailures()) {
     *     // Trigger operational alert for manual intervention
     *     alertService.sendCriticalAlert("Partial compensation failure for order " + orderId);
     * }
     * }</pre>
     *
     * @param restoredItemIds item IDs whose inventory was successfully restored
     * @param failedItemIds   item IDs whose inventory restoration failed,
     *                        requiring manual intervention
     */
    public record CompensationResult(List<String> restoredItemIds, List<String> failedItemIds) {

        /**
         * Determines whether all items were successfully compensated.
         *
         * @return {@code true} if every item in the compensation list was
         *         successfully restored (no failures), {@code false} otherwise
         */
        public boolean isFullyCompensated() {
            return failedItemIds.isEmpty();
        }

        /**
         * Determines whether any item failed to be compensated.
         *
         * <p>When this returns {@code true}, the ERROR log messages contain
         * "CRITICAL" entries for each failed item, and the caller should
         * trigger an operational alert for manual investigation.</p>
         *
         * @return {@code true} if at least one item failed to have its
         *         inventory restored, {@code false} if all succeeded
         */
        public boolean hasFailures() {
            return !failedItemIds.isEmpty();
        }
    }
}
