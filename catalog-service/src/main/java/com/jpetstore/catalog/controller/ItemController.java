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
package com.jpetstore.catalog.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jpetstore.catalog.dto.InventoryDecrementRequest;
import com.jpetstore.catalog.dto.ItemDTO;
import com.jpetstore.catalog.dto.ProductDTO;
import com.jpetstore.catalog.entity.Item;
import com.jpetstore.catalog.entity.Product;
import com.jpetstore.catalog.service.CatalogService;
import com.jpetstore.catalog.service.InventoryService;

/**
 * REST controller for the Item resource in the Catalog bounded context.
 *
 * <p>This is the most complex controller in the Catalog Service, exposing both
 * item browsing endpoints and the critical inventory management endpoints used
 * by the Order Service's Saga orchestrator during distributed order transactions.</p>
 *
 * <h3>Monolith Replacement Mapping</h3>
 * <ul>
 *   <li>{@code CatalogActionBean.viewProduct()} item listing →
 *       {@link #getItemsByProduct(String)}</li>
 *   <li>{@code CatalogActionBean.viewItem()} item detail →
 *       {@link #getItemById(String)}</li>
 *   <li>{@code CatalogService.isItemInStock()} + {@code ItemMapper.getInventoryQuantity()} →
 *       {@link #getInventory(String)}</li>
 *   <li>{@code OrderService.insertOrder()} cross-boundary inventory decrement →
 *       {@link #decrementInventory(String, InventoryDecrementRequest)}</li>
 * </ul>
 *
 * <h3>Cross-Service Contracts</h3>
 * <ul>
 *   <li><strong>GET endpoints</strong>: Called by API Gateway, monolith CatalogActionBean,
 *       monolith CartActionBean, and Order Service's CatalogServiceClient</li>
 *   <li><strong>POST inventory decrement</strong>: Called by Order Service's Saga
 *       Orchestrator (Step 2: RESERVE_INVENTORY) — returns 200 on success,
 *       409 Conflict on insufficient stock (per AAP Section 0.7.1)</li>
 *   <li><strong>POST inventory restore</strong>: Called by Order Service's
 *       InventoryCompensation for Saga rollback</li>
 * </ul>
 *
 * <h3>Design Decisions</h3>
 * <ul>
 *   <li>Returns {@link ResponseEntity} wrappers for proper HTTP status codes</li>
 *   <li>Returns DTOs, NOT JPA entities (clean API boundary)</li>
 *   <li>No {@code @Transactional} on controller methods — transactions managed
 *       by the service layer</li>
 *   <li>No cross-service database access — inventory managed through
 *       {@link InventoryService}</li>
 *   <li>No session state — fully stateless REST controller</li>
 *   <li>{@code @Valid} on POST request bodies triggers Jakarta Bean Validation</li>
 * </ul>
 *
 * @see CatalogService
 * @see InventoryService
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private static final Logger log = LoggerFactory.getLogger(ItemController.class);

    private final CatalogService catalogService;
    private final InventoryService inventoryService;

    /**
     * Constructs the ItemController with the required service dependencies.
     *
     * <p>Uses constructor injection (no {@code @Autowired} annotation needed
     * with a single constructor) consistent with Spring's recommended pattern
     * and the monolith's service class conventions.</p>
     *
     * @param catalogService   the business logic service for catalog read operations
     *                         (item listing, item detail lookup)
     * @param inventoryService the service managing atomic inventory quantity operations
     *                         (stock check, decrement, restore)
     */
    public ItemController(CatalogService catalogService, InventoryService inventoryService) {
        this.catalogService = catalogService;
        this.inventoryService = inventoryService;
    }

    // =========================================================================
    // Endpoint 1: GET /api/items?productId={productId}
    // =========================================================================

    /**
     * Retrieves all items belonging to a specified product.
     *
     * <p><strong>Monolith equivalence:</strong> Replaces
     * {@code CatalogActionBean.viewProduct()} (line 168) which calls
     * {@code catalogService.getItemListByProduct(productId)}, and the
     * monolith's {@code CatalogService.getItemListByProduct()} (lines 79-81)
     * which passes through to {@code itemMapper.getItemListByProduct(productId)}.</p>
     *
     * <p>Each item in the response includes:</p>
     * <ul>
     *   <li>All item fields (ID, price, cost, status, attributes 1-5)</li>
     *   <li>Nested {@link ProductDTO} with product details</li>
     *   <li>Current inventory quantity loaded from the separate Inventory entity</li>
     * </ul>
     *
     * @param productId the product identifier to filter items by (e.g., "FI-SW-01");
     *                  required query parameter
     * @return HTTP 200 with a JSON array of {@link ItemDTO} objects;
     *         empty array if no items match the product
     */
    @GetMapping
    public ResponseEntity<List<ItemDTO>> getItemsByProduct(
            @RequestParam("productId") String productId) {
        log.debug("GET /api/items?productId={} — fetching items by product", productId);
        List<Item> items = catalogService.getItemListByProduct(productId);
        List<ItemDTO> dtos = items.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        log.debug("Returning {} items for product '{}'", dtos.size(), productId);
        return ResponseEntity.ok(dtos);
    }

    // =========================================================================
    // Endpoint 2: GET /api/items/{id}
    // =========================================================================

    /**
     * Retrieves a single item by its unique identifier, including product info
     * and current inventory quantity.
     *
     * <p><strong>Monolith equivalence:</strong> Replaces
     * {@code CatalogActionBean.viewItem()} (lines 179-182) which calls
     * {@code catalogService.getItem(itemId)} then accesses
     * {@code item.getProduct()}. In the monolith, {@code getItem()} performed
     * a SQL JOIN to fetch item + product + inventory quantity together.</p>
     *
     * <p>In the decomposed architecture, the Item entity does NOT have a
     * quantity field. The {@link #toDTO(Item)} helper separately fetches
     * quantity from {@link InventoryService}.</p>
     *
     * <p>This endpoint is also called by Order Service's
     * {@code CatalogServiceClient} via {@code GET /api/items/{id}}
     * (per AAP Section 0.5.2).</p>
     *
     * @param itemId the item identifier (e.g., "EST-1", "EST-14")
     * @return HTTP 200 with the {@link ItemDTO} if found;
     *         HTTP 404 if no item exists with the given ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ItemDTO> getItemById(@PathVariable("id") String itemId) {
        log.debug("GET /api/items/{} — fetching item", itemId);
        Item item = catalogService.getItem(itemId);
        if (item == null) {
            log.debug("Item not found: {}", itemId);
            return ResponseEntity.notFound().build();
        }
        log.debug("Item found: {}", itemId);
        return ResponseEntity.ok(toDTO(item));
    }

    // =========================================================================
    // Endpoint 3: GET /api/items/{id}/inventory
    // =========================================================================

    /**
     * Retrieves the current inventory stock level for a specific item.
     *
     * <p><strong>Monolith equivalence:</strong> Replaces
     * {@code CatalogService.isItemInStock()} (lines 87-89) which called
     * {@code itemMapper.getInventoryQuantity(itemId) > 0}. This REST endpoint
     * exposes the actual quantity AND the boolean {@code inStock} flag for
     * more flexibility.</p>
     *
     * <p>Response format:</p>
     * <pre>
     * {
     *   "itemId": "EST-1",
     *   "quantity": 10000,
     *   "inStock": true
     * }
     * </pre>
     *
     * <p>Always returns HTTP 200 — a quantity of 0 means out of stock,
     * not "not found". This matches the monolith's behavior where missing
     * inventory records effectively mean zero stock.</p>
     *
     * @param itemId the item identifier to check inventory for
     * @return HTTP 200 with a JSON object containing {@code itemId},
     *         {@code quantity} (int), and {@code inStock} (boolean)
     */
    @GetMapping("/{id}/inventory")
    public ResponseEntity<Map<String, Object>> getInventory(@PathVariable("id") String itemId) {
        log.debug("GET /api/items/{}/inventory — fetching inventory quantity", itemId);
        int quantity = inventoryService.getInventoryQuantity(itemId);
        log.debug("Inventory quantity for item '{}': {} (inStock={})", itemId, quantity, quantity > 0);
        return ResponseEntity.ok(Map.of(
                "itemId", itemId,
                "quantity", quantity,
                "inStock", quantity > 0
        ));
    }

    // =========================================================================
    // Endpoint 4: POST /api/items/{id}/inventory/decrement (Saga Step)
    // =========================================================================

    /**
     * Atomically decrements the inventory quantity for a specific item as part
     * of the order placement Saga.
     *
     * <p><strong>Monolith equivalence:</strong> Replaces the cross-boundary
     * inventory decrement in {@code OrderService.insertOrder()} (lines 62-68)
     * which called {@code itemMapper.updateInventoryQuantity()} in a loop for
     * each line item, with the SQL: {@code UPDATE INVENTORY SET QTY = QTY -
     * #{increment} WHERE ITEMID = #{itemId}}.</p>
     *
     * <p><strong>Saga Integration (AAP Section 0.7.1):</strong></p>
     * <ul>
     *   <li>This is the <strong>critical</strong> cross-service endpoint called by
     *       Order Service's Saga Orchestrator during Step 2 (RESERVE_INVENTORY)</li>
     *   <li>The {@code orderId} in the request body serves as the <strong>idempotency
     *       key</strong> — duplicate requests with the same orderId and itemId return
     *       success without double-decrementing</li>
     *   <li>On success (200): Saga proceeds to confirm the order</li>
     *   <li>On insufficient stock (409): Saga marks the order as FAILED — no
     *       compensation needed since inventory was NOT decremented</li>
     * </ul>
     *
     * <p><strong>Validation:</strong> The {@code @Valid} annotation triggers Jakarta
     * Bean Validation on the request body:
     * <ul>
     *   <li>{@code quantity}: {@code @NotNull @Min(1)} — must be at least 1</li>
     *   <li>{@code orderId}: {@code @NotBlank} — Saga idempotency key, must not
     *       be empty</li>
     * </ul>
     * If validation fails, Spring Boot automatically returns 400 Bad Request.</p>
     *
     * @param itemId  the item identifier whose inventory to decrement
     * @param request the decrement request containing {@code quantity} and
     *                {@code orderId} (idempotency key)
     * @return HTTP 200 with confirmation details if the decrement succeeded
     *         (or was already applied for this orderId);
     *         HTTP 409 Conflict with error details if insufficient stock
     */
    @PostMapping("/{id}/inventory/decrement")
    public ResponseEntity<Map<String, Object>> decrementInventory(
            @PathVariable("id") String itemId,
            @Valid @RequestBody InventoryDecrementRequest request) {
        log.info("POST /api/items/{}/inventory/decrement — quantity={}, orderId={}",
                itemId, request.getQuantity(), request.getOrderId());

        boolean success = inventoryService.decrementInventory(
                itemId, request.getQuantity(), request.getOrderId());

        if (success) {
            log.info("Inventory decrement SUCCESS for item '{}', quantity={}, orderId={}",
                    itemId, request.getQuantity(), request.getOrderId());
            return ResponseEntity.ok(Map.of(
                    "itemId", itemId,
                    "decremented", request.getQuantity(),
                    "orderId", request.getOrderId(),
                    "status", "SUCCESS"
            ));
        } else {
            log.warn("Inventory decrement FAILED for item '{}': insufficient stock or item not found. "
                    + "requestedDecrement={}, orderId={}",
                    itemId, request.getQuantity(), request.getOrderId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "itemId", itemId,
                    "requestedDecrement", request.getQuantity(),
                    "orderId", request.getOrderId(),
                    "status", "INSUFFICIENT_STOCK"
            ));
        }
    }

    // =========================================================================
    // Endpoint 5: POST /api/items/{id}/inventory/restore (Saga Compensation)
    // =========================================================================

    /**
     * Restores the inventory quantity for a specific item as part of the
     * Saga compensation (rollback) for a failed order.
     *
     * <p>This endpoint is consumed by the Order Service's
     * {@code InventoryCompensation} when an order fails after inventory was
     * already decremented. It reverses the decrement by adding the quantity
     * back and removing the reservation record.</p>
     *
     * <p>Per AAP Section 0.7.1: the compensating transaction calls
     * {@code POST /api/items/{id}/inventory/restore} for each decremented item.</p>
     *
     * @param itemId  the item identifier to restore inventory for
     * @param request the restore request containing {@code quantity} and
     *                {@code orderId} (matches the original decrement)
     * @return HTTP 200 on successful restoration
     */
    @PostMapping("/{id}/inventory/restore")
    public ResponseEntity<Void> restoreInventory(
            @PathVariable("id") String itemId,
            @Valid @RequestBody InventoryDecrementRequest request) {
        log.info("POST /api/items/{}/inventory/restore — quantity={}, orderId={}",
                itemId, request.getQuantity(), request.getOrderId());
        inventoryService.restoreInventory(itemId, request.getQuantity(), request.getOrderId());
        log.info("Inventory restored for item '{}', orderId='{}'", itemId, request.getOrderId());
        return ResponseEntity.ok().build();
    }

    // =========================================================================
    // Exception Handlers
    // =========================================================================

    /**
     * Handles {@link IllegalArgumentException} thrown by inventory operations
     * when validation fails — e.g., attempting to restore inventory for an
     * orderId that has no matching decrement reservation.
     *
     * <p>Returns HTTP 409 Conflict with a JSON error body containing the
     * exception message. This is the appropriate status code because the
     * request conflicts with the current state of the resource (no matching
     * reservation exists to restore against).</p>
     *
     * @param ex the exception thrown by the inventory service
     * @return HTTP 409 with error details
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex) {
        log.warn("Inventory operation rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Conflict",
                "message", ex.getMessage(),
                "status", 409
        ));
    }

    // =========================================================================
    // Private Helper Methods — Entity-to-DTO Mapping
    // =========================================================================

    /**
     * Converts an {@link Item} JPA entity to an {@link ItemDTO} for API responses.
     *
     * <p><strong>CRITICAL COMPLEXITY:</strong> The Item entity has
     * {@code @ManyToOne Product} and {@code @ManyToOne Supplier} relationships
     * that must be flattened. Additionally, the {@code quantity} field is NOT on
     * the Item entity — it must be fetched separately from
     * {@link InventoryService}.</p>
     *
     * <p>Entity-to-DTO field mapping:</p>
     * <ul>
     *   <li>{@code Item.itemId} → {@code ItemDTO.itemId}</li>
     *   <li>{@code Item.product.productId} → {@code ItemDTO.productId}
     *       (flattened, null-safe)</li>
     *   <li>{@code Item.listPrice} → {@code ItemDTO.listPrice}</li>
     *   <li>{@code Item.unitCost} → {@code ItemDTO.unitCost}</li>
     *   <li>{@code Item.supplier.suppId} → {@code ItemDTO.supplierId}
     *       (flattened from Integer to int, defaults to 0 if null)</li>
     *   <li>{@code Item.status} → {@code ItemDTO.status}</li>
     *   <li>{@code Item.attribute1-5} → {@code ItemDTO.attribute1-5}</li>
     *   <li>{@code Item.product} → {@code ItemDTO.product} (nested ProductDTO)</li>
     *   <li>{@code Inventory.qty} (separate entity) → {@code ItemDTO.quantity}
     *       (fetched from InventoryService, returns 0 if not found)</li>
     * </ul>
     *
     * @param item the entity to convert; must not be {@code null}
     * @return the fully populated {@link ItemDTO} with inventory quantity and
     *         nested product information
     */
    private ItemDTO toDTO(Item item) {
        ItemDTO dto = new ItemDTO();
        dto.setItemId(item.getItemId());

        // Flatten Product @ManyToOne → productId String (null-safe)
        dto.setProductId(item.getProduct() != null
                ? item.getProduct().getProductId() : null);

        dto.setListPrice(item.getListPrice());
        dto.setUnitCost(item.getUnitCost());

        // Flatten Supplier @ManyToOne → supplierId int (null-safe, default 0)
        dto.setSupplierId(item.getSupplier() != null
                && item.getSupplier().getSuppId() != null
                ? item.getSupplier().getSuppId() : 0);

        dto.setStatus(item.getStatus());
        dto.setAttribute1(item.getAttribute1());
        dto.setAttribute2(item.getAttribute2());
        dto.setAttribute3(item.getAttribute3());
        dto.setAttribute4(item.getAttribute4());
        dto.setAttribute5(item.getAttribute5());

        // Nested ProductDTO (includes Category → categoryId flattening)
        if (item.getProduct() != null) {
            dto.setProduct(toProductDTO(item.getProduct()));
        }

        // Quantity from separate Inventory entity — NOT on Item entity.
        // In the monolith, this was populated by a SQL JOIN in ItemMapper.xml:
        //   SELECT ... QTY AS quantity ... FROM ITEM, INVENTORY WHERE ...
        // In the decomposed architecture, Item and Inventory are separate entities.
        int quantity = inventoryService.getInventoryQuantity(item.getItemId());
        dto.setQuantity(quantity);

        return dto;
    }

    /**
     * Converts a {@link Product} JPA entity to a {@link ProductDTO} for nesting
     * inside an {@link ItemDTO}.
     *
     * <p>Flattens the Product entity's {@code @ManyToOne Category} relationship
     * to a plain {@code categoryId} String via
     * {@code product.getCategory().getCatId()} (null-safe).</p>
     *
     * <p>This is the same mapping logic as {@code ProductController.toDTO()} —
     * duplicated here to keep controllers independent with no shared mapping
     * utility class, avoiding unnecessary coupling between controllers.</p>
     *
     * @param product the product entity to convert; must not be {@code null}
     * @return the populated {@link ProductDTO}
     */
    private ProductDTO toProductDTO(Product product) {
        ProductDTO dto = new ProductDTO();
        dto.setProductId(product.getProductId());
        dto.setCategoryId(product.getCategory() != null
                ? product.getCategory().getCatId() : null);
        dto.setName(product.getName());
        dto.setDescription(product.getDescription());
        return dto;
    }
}
