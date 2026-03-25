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
package com.jpetstore.order.client;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * REST client component for communicating with the Catalog Service microservice.
 *
 * <p>This is the <b>most critical cross-service client</b> in the Order Service
 * because it handles the distributed inventory management that was previously an
 * in-process call within the monolith's single {@code @Transactional} boundary
 * in {@code OrderService.insertOrder()}.</p>
 *
 * <h3>Monolith Pattern Being Replaced</h3>
 * <p>In the monolith, {@code OrderService.insertOrder()} executed inventory
 * decrements directly via {@code itemMapper.updateInventoryQuantity(param)}
 * within the same ACID transaction as order writes. The underlying SQL was:</p>
 * <pre>
 * UPDATE INVENTORY SET QTY = QTY - #{increment} WHERE ITEMID = #{itemId}
 * </pre>
 * <p>That single-process call is now replaced by REST calls to the Catalog
 * Service, coordinated via the orchestration-based Saga pattern.</p>
 *
 * <h3>Saga Pattern Integration</h3>
 * <p>This client participates in the order placement Saga as follows:</p>
 * <ol>
 *   <li><b>Forward step (Saga Step 2):</b> {@link #decrementInventory(String, int, String)}
 *       calls {@code POST /api/items/{id}/inventory/decrement} for each line item</li>
 *   <li><b>Compensating action:</b> {@link #restoreInventory(String, int, String)}
 *       calls {@code POST /api/items/{id}/inventory/restore} to undo decrements
 *       on order failure</li>
 *   <li><b>Item lookup:</b> {@link #getItem(String)} calls {@code GET /api/items/{id}}
 *       to retrieve item details for order enrichment</li>
 * </ol>
 *
 * <h3>Idempotency Key Design</h3>
 * <p>Per AAP Section 0.7.1, every inventory reservation request includes the
 * {@code orderId} as an idempotency key. The Catalog Service stores reservation
 * records indexed by {@code orderId} and returns success for duplicate requests
 * without double-decrementing. This ensures retries are safe after network
 * timeouts where the server may have already processed the request.</p>
 *
 * <h3>Error Handling Strategy</h3>
 * <table border="1">
 *   <tr><th>HTTP Status</th><th>Meaning</th><th>Action</th></tr>
 *   <tr><td>200</td><td>Success</td><td>Proceed normally</td></tr>
 *   <tr><td>404</td><td>Item not found</td><td>Return empty/false</td></tr>
 *   <tr><td>409</td><td>Insufficient stock</td><td>Return false (deterministic failure)</td></tr>
 *   <tr><td>5xx/timeout</td><td>Service error</td><td>Throw for decrement, return false for restore</td></tr>
 * </table>
 *
 * <h3>Cross-Service Data Access Rule</h3>
 * <p>Per AAP Section 0.8.1: "No service may access another service's database
 * directly." This client is the <b>only</b> way the Order Service accesses
 * catalog/inventory data. All inventory operations go through REST calls to
 * the Catalog Service.</p>
 *
 * @see com.jpetstore.order.config.AppConfig#catalogServiceRestClient
 * @see com.jpetstore.order.saga.OrderSagaOrchestrator
 * @see com.jpetstore.order.saga.InventoryCompensation
 */
@Component
public class CatalogServiceClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogServiceClient.class);

    /**
     * Pre-configured RestClient for the Catalog Service with base URL
     * {@code http://catalog-service:8082} and timeouts (5s connect, 10s read).
     * Defined in {@link com.jpetstore.order.config.AppConfig#catalogServiceRestClient}.
     */
    private final RestClient restClient;

    /**
     * Constructs a new {@code CatalogServiceClient} with the specified RestClient.
     *
     * <p>The {@code @Qualifier("catalogServiceRestClient")} annotation selects the
     * specific RestClient bean configured for the Catalog Service, distinguishing
     * it from the {@code accountServiceRestClient} bean also defined in
     * {@link com.jpetstore.order.config.AppConfig}.</p>
     *
     * @param restClient the RestClient instance pre-configured with the Catalog
     *                   Service base URL and timeout settings
     */
    public CatalogServiceClient(@Qualifier("catalogServiceRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * Retrieves item details from the Catalog Service by item ID.
     *
     * <p>This mirrors the monolith's {@code CatalogService.getItem(String itemId)}
     * which delegated to {@code itemMapper.getItem(itemId)}. In the decomposed
     * architecture, this method calls {@code GET /api/items/{id}} on the Catalog
     * Service.</p>
     *
     * <p>Used by {@code OrderService.getOrder()} to enrich line items with item
     * details (replicating monolith lines 92-96 where each line item's Item and
     * inventory quantity were fetched inline).</p>
     *
     * <p>The response is deserialized to {@code Map<String, Object>} rather than
     * a typed DTO to avoid compile-time dependency on the catalog-service module's
     * classes. The map contains item fields such as itemId, productId, listPrice,
     * unitCost, status, attribute1-5, product info, and quantity.</p>
     *
     * @param itemId the unique identifier of the item to retrieve (e.g., "EST-1")
     * @return an {@link Optional} containing the item data map if found,
     *         or {@link Optional#empty()} if the item does not exist (HTTP 404)
     *         or if the Catalog Service is unavailable (graceful degradation)
     */
    public Optional<Map<String, Object>> getItem(String itemId) {
        try {
            Map<String, Object> item = restClient.get()
                    .uri("/api/items/{id}", itemId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return Optional.ofNullable(item);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Item not found in Catalog Service: itemId={}", itemId);
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("Error calling Catalog Service to retrieve item: itemId={}", itemId, e);
            return Optional.empty();
        }
    }

    /**
     * Decrements inventory for a specific item during order placement (Saga Step 2).
     *
     * <p>This is the <b>critical Saga forward step</b> that replaces the monolith's
     * direct {@code itemMapper.updateInventoryQuantity(param)} call. In the monolith,
     * the inventory decrement was executed within a single {@code @Transactional}
     * boundary in {@code OrderService.insertOrder()} (lines 62-69). In the decomposed
     * architecture, this cross-bounded-context operation is performed via a REST call
     * to the Catalog Service.</p>
     *
     * <p>The underlying Catalog Service endpoint executes the equivalent SQL:</p>
     * <pre>
     * UPDATE inventory SET qty = qty - :quantity WHERE itemid = :itemId AND qty >= :quantity
     * </pre>
     *
     * <h4>Idempotency Key</h4>
     * <p>The {@code orderId} parameter serves as the idempotency key per AAP Section
     * 0.7.1. The Catalog Service stores reservation records indexed by orderId and
     * returns HTTP 200 for duplicate requests without double-decrementing inventory.
     * This ensures that retries after network timeouts are safe.</p>
     *
     * <h4>Error Classification (CRITICAL for Saga)</h4>
     * <ul>
     *   <li><b>HTTP 200:</b> Success — inventory decremented, returns {@code true}</li>
     *   <li><b>HTTP 409 (Conflict):</b> Insufficient stock — deterministic failure,
     *       returns {@code false}. The Saga marks the order as FAILED. No inventory
     *       was decremented, so no compensation is needed.</li>
     *   <li><b>HTTP 5xx / connection error:</b> Indeterminate failure — throws
     *       {@link RuntimeException}. The Saga orchestrator catches this and decides
     *       whether to compensate (the order remains in PENDING status for
     *       reconciliation).</li>
     * </ul>
     *
     * <p>The distinction between 409 (return false) and 5xx (throw) is critical:
     * 409 is a deterministic failure where no inventory change occurred, while 5xx
     * is an indeterminate failure where inventory may or may not have been decremented.</p>
     *
     * @param itemId   the unique identifier of the item whose inventory to decrement
     *                 (e.g., "EST-1")
     * @param quantity the number of units to decrement (matches the line item quantity)
     * @param orderId  the order ID serving as the idempotency key to prevent
     *                 double-decrementing on retries
     * @return {@code true} if inventory was successfully decremented (HTTP 200),
     *         {@code false} if insufficient stock (HTTP 409)
     * @throws RuntimeException if the Catalog Service is unavailable or returns a
     *                          server error (HTTP 5xx, connection timeout), indicating
     *                          the Saga orchestrator should initiate compensation
     */
    public boolean decrementInventory(String itemId, int quantity, String orderId) {
        Map<String, Object> requestBody = Map.of(
                "quantity", quantity,
                "orderId", orderId
        );

        try {
            restClient.post()
                    .uri("/api/items/{id}/inventory/decrement", itemId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Inventory decremented successfully: itemId={}, quantity={}, orderId={}",
                    itemId, quantity, orderId);
            return true;
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("Insufficient stock for inventory decrement: itemId={}, requestedQuantity={}, orderId={}",
                    itemId, quantity, orderId);
            return false;
        } catch (RestClientException e) {
            log.error("Error calling Catalog Service to decrement inventory: itemId={}, quantity={}, orderId={}",
                    itemId, quantity, orderId, e);
            throw new RuntimeException(
                    "Catalog Service unavailable for inventory decrement: itemId=" + itemId
                            + ", orderId=" + orderId, e);
        }
    }

    /**
     * Restores (increments) inventory for a specific item as a Saga compensating action.
     *
     * <p>This is the compensating transaction for {@link #decrementInventory(String, int, String)}.
     * When an order fails after one or more inventory decrements have succeeded, this
     * method is called for each previously decremented item to restore the inventory
     * to its pre-order state.</p>
     *
     * <p>Per AAP Section 0.7.1, this is the compensating transaction: "InventoryCompensation
     * calls {@code POST /api/items/{id}/inventory/restore} for each decremented item."</p>
     *
     * <h4>Idempotency</h4>
     * <p>The {@code orderId} is included in the request body to ensure compensation
     * is applied exactly once. Duplicate restoration calls with the same orderId are
     * safely handled by the Catalog Service without double-restoring inventory.</p>
     *
     * <h4>Error Handling (Best-Effort Compensation)</h4>
     * <p>Unlike {@link #decrementInventory(String, int, String)}, this method
     * <b>never throws exceptions</b>. Compensation must be best-effort: if the
     * restoration call fails, the failure is logged at ERROR level with a "CRITICAL"
     * prefix indicating manual intervention is required. The method returns
     * {@code false} to signal the failure, but does not propagate the exception
     * to avoid cascading failures in the compensation chain.</p>
     *
     * @param itemId   the unique identifier of the item whose inventory to restore
     *                 (e.g., "EST-1")
     * @param quantity the number of units to restore (same quantity that was decremented)
     * @param orderId  the order ID for idempotency — ensures restoration is applied
     *                 exactly once per order
     * @return {@code true} if inventory was successfully restored (HTTP 200),
     *         {@code false} if the restoration failed (any error — manual intervention
     *         required)
     */
    public boolean restoreInventory(String itemId, int quantity, String orderId) {
        Map<String, Object> requestBody = Map.of(
                "quantity", quantity,
                "orderId", orderId
        );

        try {
            restClient.post()
                    .uri("/api/items/{id}/inventory/restore", itemId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Inventory restored successfully: itemId={}, quantity={}, orderId={}",
                    itemId, quantity, orderId);
            return true;
        } catch (RestClientException e) {
            log.error("CRITICAL: Failed to restore inventory — manual intervention required: "
                            + "itemId={}, quantity={}, orderId={}",
                    itemId, quantity, orderId, e);
            return false;
        }
    }
}
