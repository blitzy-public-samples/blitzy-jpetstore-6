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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
import com.jpetstore.catalog.service.CatalogService;
import com.jpetstore.catalog.service.InventoryService;

import jakarta.validation.Valid;

/**
 * REST controller for the Item resource in the Catalog bounded context.
 *
 * <p>Exposes item browsing and inventory management endpoints that replicate
 * the monolith's {@code CatalogActionBean} item operations and the
 * cross-service inventory operations used by the Order Service's Saga
 * orchestrator.</p>
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/items?productId=} — List items by product</li>
 *   <li>{@code GET /api/items/{id}} — Get a single item by ID</li>
 *   <li>{@code GET /api/items/{id}/inventory} — Get inventory quantity for an item</li>
 *   <li>{@code POST /api/items/{id}/inventory/decrement} — Decrement inventory (Saga step)</li>
 *   <li>{@code POST /api/items/{id}/inventory/restore} — Restore inventory (Saga compensation)</li>
 * </ul>
 *
 * <p>The inventory decrement and restore endpoints are consumed by the Order Service's
 * {@code OrderSagaOrchestrator} during the distributed order transaction. The decrement
 * endpoint uses an idempotency key ({@code orderId}) to prevent double-decrements on
 * retries, and the restore endpoint acts as the compensating transaction for failed orders.</p>
 *
 * @see com.jpetstore.catalog.service.CatalogService
 * @see com.jpetstore.catalog.service.InventoryService
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private static final Logger log = LoggerFactory.getLogger(ItemController.class);

    private final CatalogService catalogService;
    private final InventoryService inventoryService;

    /**
     * Constructs the ItemController with the required services.
     *
     * @param catalogService   the business logic service for catalog read operations
     * @param inventoryService the service managing inventory quantity operations
     */
    public ItemController(CatalogService catalogService, InventoryService inventoryService) {
        this.catalogService = catalogService;
        this.inventoryService = inventoryService;
    }

    /**
     * Retrieves all items belonging to a specified product.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.viewProduct()} which calls
     * {@code catalogService.getItemListByProduct(productId)} and populates the item
     * list displayed in Product.jsp.</p>
     *
     * <p>Each item in the response includes the current inventory quantity, loaded
     * from the separate Inventory entity to populate the {@code quantity} field
     * in {@link ItemDTO}.</p>
     *
     * @param productId the product identifier to filter items by (e.g., "FI-SW-01")
     * @return HTTP 200 with a JSON array of {@link ItemDTO} objects;
     *         empty array if no items match the product
     */
    @GetMapping(params = "productId")
    public ResponseEntity<List<ItemDTO>> getItemsByProduct(@RequestParam String productId) {
        log.debug("GET /api/items?productId={} — fetching items by product", productId);
        List<Item> items = catalogService.getItemListByProduct(productId);
        List<ItemDTO> dtos = items.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        log.debug("Returning {} items for product '{}'", dtos.size(), productId);
        return ResponseEntity.ok(dtos);
    }

    /**
     * Retrieves a single item by its unique identifier.
     *
     * <p>Replaces the monolith's {@code CatalogActionBean.viewItem()} which calls
     * {@code catalogService.getItem(itemId)} and renders Item.jsp.
     * The response includes the current inventory quantity and the associated
     * product details.</p>
     *
     * @param id the item identifier (e.g., "EST-1", "EST-14")
     * @return HTTP 200 with the {@link ItemDTO} if found;
     *         HTTP 404 if no item exists with the given ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<ItemDTO> getItemById(@PathVariable String id) {
        log.debug("GET /api/items/{} — fetching item", id);
        Item item = catalogService.getItem(id);
        if (item != null) {
            log.debug("Item found: {}", id);
            return ResponseEntity.ok(toDTO(item));
        } else {
            log.debug("Item not found: {}", id);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Retrieves the current inventory quantity for a specific item.
     *
     * <p>Replaces the monolith's {@code itemMapper.getInventoryQuantity(itemId)}
     * call. Returns the raw integer quantity value for the Order Service or
     * other consumers that need to check stock levels.</p>
     *
     * @param id the item identifier to check inventory for
     * @return HTTP 200 with the integer quantity value (0 if item not found in inventory)
     */
    @GetMapping("/{id}/inventory")
    public ResponseEntity<Integer> getInventoryQuantity(@PathVariable String id) {
        log.debug("GET /api/items/{}/inventory — fetching inventory quantity", id);
        int quantity = inventoryService.getInventoryQuantity(id);
        log.debug("Inventory quantity for item '{}': {}", id, quantity);
        return ResponseEntity.ok(quantity);
    }

    /**
     * Decrements the inventory quantity for a specific item as part of the
     * order placement Saga.
     *
     * <p>This endpoint is consumed by the Order Service's {@code OrderSagaOrchestrator}
     * during Step 2 (RESERVE_INVENTORY) of the distributed order transaction.
     * It uses optimistic locking ({@code @Version}) and an idempotency key
     * ({@code orderId}) to safely handle concurrent requests and retries.</p>
     *
     * <p><strong>Idempotency:</strong> If a decrement request with the same
     * {@code orderId} and {@code itemId} has already been processed, the endpoint
     * returns {@code true} without double-decrementing. This is enforced by a
     * unique constraint on the {@code inventory_reservation} table.</p>
     *
     * <p><strong>Oversell protection:</strong> The underlying SQL uses a
     * {@code WHERE qty >= :decrement} guard to prevent negative inventory.
     * If insufficient stock, returns {@code false} and no decrement occurs.</p>
     *
     * @param id      the item identifier to decrement inventory for
     * @param request the decrement request containing quantity and orderId (idempotency key)
     * @return HTTP 200 with {@code true} if the decrement succeeded (or was already applied);
     *         HTTP 200 with {@code false} if insufficient inventory
     */
    @PostMapping("/{id}/inventory/decrement")
    public ResponseEntity<Boolean> decrementInventory(
            @PathVariable String id,
            @Valid @RequestBody InventoryDecrementRequest request) {
        log.info("POST /api/items/{}/inventory/decrement — quantity={}, orderId={}",
                id, request.getQuantity(), request.getOrderId());
        boolean success = inventoryService.decrementInventory(
                id, request.getQuantity(), request.getOrderId());
        log.info("Inventory decrement for item '{}': {}", id, success ? "SUCCESS" : "INSUFFICIENT_STOCK");
        return ResponseEntity.ok(success);
    }

    /**
     * Restores the inventory quantity for a specific item as part of the
     * Saga compensation (rollback) for a failed order.
     *
     * <p>This endpoint is consumed by the Order Service's {@code InventoryCompensation}
     * when an order fails after inventory was already decremented. It reverses the
     * decrement by adding the quantity back and removing the reservation record.</p>
     *
     * <p><strong>Idempotency:</strong> If no reservation record exists for the
     * given {@code orderId} and {@code itemId}, the restore is a no-op (the
     * decrement was never applied or was already compensated).</p>
     *
     * @param id      the item identifier to restore inventory for
     * @param request the restore request containing quantity and orderId (matches the original decrement)
     * @return HTTP 200 (no body) on successful restoration or no-op
     */
    @PostMapping("/{id}/inventory/restore")
    public ResponseEntity<Void> restoreInventory(
            @PathVariable String id,
            @Valid @RequestBody InventoryDecrementRequest request) {
        log.info("POST /api/items/{}/inventory/restore — quantity={}, orderId={}",
                id, request.getQuantity(), request.getOrderId());
        inventoryService.restoreInventory(id, request.getQuantity(), request.getOrderId());
        log.info("Inventory restored for item '{}', orderId='{}'", id, request.getOrderId());
        return ResponseEntity.ok().build();
    }

    /**
     * Converts an Item entity to an ItemDTO for API responses.
     *
     * <p>Maps entity field names to DTO field names. The product details are
     * included as a nested {@link ProductDTO}. The inventory quantity is loaded
     * separately from the Inventory entity via the CatalogService.</p>
     *
     * <p><strong>Entity-to-DTO field mapping:</strong></p>
     * <ul>
     *   <li>{@code Item.itemId} → {@code ItemDTO.itemId}</li>
     *   <li>{@code Item.product.productId} → {@code ItemDTO.productId}</li>
     *   <li>{@code Item.listPrice} → {@code ItemDTO.listPrice}</li>
     *   <li>{@code Item.unitCost} → {@code ItemDTO.unitCost}</li>
     *   <li>{@code Item.supplier.suppid} → {@code ItemDTO.supplierId}</li>
     *   <li>{@code Item.status} → {@code ItemDTO.status}</li>
     *   <li>{@code Item.attribute1-5} → {@code ItemDTO.attribute1-5}</li>
     *   <li>{@code Inventory.qty} (separate entity) → {@code ItemDTO.quantity}</li>
     *   <li>{@code Item.product} → {@code ItemDTO.product} (as nested ProductDTO)</li>
     * </ul>
     *
     * @param item the entity to convert (must not be null)
     * @return the populated ItemDTO with inventory quantity and nested product
     */
    private ItemDTO toDTO(Item item) {
        ItemDTO dto = new ItemDTO();
        dto.setItemId(item.getItemId());
        dto.setListPrice(item.getListPrice());
        dto.setUnitCost(item.getUnitCost());
        dto.setStatus(item.getStatus());
        dto.setAttribute1(item.getAttribute1());
        dto.setAttribute2(item.getAttribute2());
        dto.setAttribute3(item.getAttribute3());
        dto.setAttribute4(item.getAttribute4());
        dto.setAttribute5(item.getAttribute5());

        // Map product association
        if (item.getProduct() != null) {
            dto.setProductId(item.getProduct().getProductId());
            ProductDTO productDTO = new ProductDTO();
            productDTO.setProductId(item.getProduct().getProductId());
            productDTO.setName(item.getProduct().getName());
            productDTO.setDescription(item.getProduct().getDescription());
            if (item.getProduct().getCategory() != null) {
                productDTO.setCategoryId(item.getProduct().getCategory().getCatId());
            }
            dto.setProduct(productDTO);
        }

        // Map supplier association
        if (item.getSupplier() != null) {
            dto.setSupplierId(item.getSupplier().getSuppId());
        }

        // Load inventory quantity from separate Inventory entity
        int quantity = inventoryService.getInventoryQuantity(item.getItemId());
        dto.setQuantity(quantity);

        return dto;
    }
}
