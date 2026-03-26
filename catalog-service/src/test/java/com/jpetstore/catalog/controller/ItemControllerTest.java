/*
 * ItemControllerTest.java — @WebMvcTest Unit Tests for ItemController (Catalog Service)
 *
 * Verifies 4 REST endpoints exposed by ItemController:
 *   GET  /api/items?productId=       — list items by product
 *   GET  /api/items/{id}             — get single item (with nested ProductDTO + quantity)
 *   GET  /api/items/{id}/inventory   — stock check (quantity + inStock boolean)
 *   POST /api/items/{id}/inventory/decrement — Saga inventory decrement (200/409/400)
 *
 * Uses @WebMvcTest to load ONLY the web layer slice (DispatcherServlet, Jackson,
 * argument resolvers, bean validation). Both CatalogService and InventoryService
 * are replaced with Mockito mocks via @MockBean.
 *
 * Key architectural difference from the monolith:
 *   - quantity in ItemDTO comes from InventoryService.getInventoryQuantity(),
 *     NOT from the Item entity. The monolith JOINed item+inventory in SQL;
 *     the decomposed version fetches them separately.
 *   - POST decrement supports the Order Service's Saga orchestrator with an
 *     orderId idempotency key (AAP Section 0.7.1).
 */
package com.jpetstore.catalog.controller;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpetstore.catalog.config.SecurityConfig;
import com.jpetstore.catalog.entity.Category;
import com.jpetstore.catalog.entity.Item;
import com.jpetstore.catalog.entity.Product;
import com.jpetstore.catalog.entity.Supplier;
import com.jpetstore.catalog.service.CatalogService;
import com.jpetstore.catalog.service.InventoryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unit tests for {@link ItemController} — the Catalog Service REST controller handling
 * item retrieval and inventory operations.
 *
 * <p>This test class covers 10 test cases across 4 REST endpoints:
 * <ol>
 *   <li>{@code GET /api/items?productId=} — list items by product (1 test)</li>
 *   <li>{@code GET /api/items/{id}} — get single item or 404 (2 tests)</li>
 *   <li>{@code GET /api/items/{id}/inventory} — stock check with inStock boolean (2 tests)</li>
 *   <li>{@code POST /api/items/{id}/inventory/decrement} — Saga inventory decrement (5 tests)</li>
 * </ol>
 *
 * <p>The POST decrement endpoint supports the Order Service's Saga orchestrator with
 * an {@code orderId} idempotency key. Tests verify the Saga contract: 200 on success,
 * 409 on insufficient stock, 400 on validation failure (per AAP Section 0.7.1).
 *
 * @see ItemController
 * @see CatalogService
 * @see InventoryService
 */
@WebMvcTest(ItemController.class)
@Import(SecurityConfig.class)
class ItemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CatalogService catalogService;

    @MockBean
    private InventoryService inventoryService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // GET /api/items?productId= — List items by product
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code GET /api/items?productId=FI-SW-01} returns 200 OK with a
     * JSON array of {@code ItemDTO} objects, each containing nested {@code ProductDTO}
     * and a {@code quantity} field populated from {@link InventoryService}.
     *
     * <p>Replaces monolith's {@code CatalogActionBean.viewProduct()} (line 168):
     * {@code itemList = catalogService.getItemListByProduct(productId)}
     */
    @Test
    void shouldReturnItemsByProduct() throws Exception {
        // Arrange — create two items for product "FI-SW-01"
        Item item1 = createItem("EST-1", "FI-SW-01", "FISH", 1,
                new BigDecimal("16.50"), new BigDecimal("10.00"), "P", "Large");
        Item item2 = createItem("EST-2", "FI-SW-01", "FISH", 1,
                new BigDecimal("16.50"), new BigDecimal("10.00"), "P", "Small");

        List<Item> items = Arrays.asList(item1, item2);
        when(catalogService.getItemListByProduct("FI-SW-01")).thenReturn(items);
        when(inventoryService.getInventoryQuantity("EST-1")).thenReturn(10000);
        when(inventoryService.getInventoryQuantity("EST-2")).thenReturn(5000);

        // Act & Assert
        mockMvc.perform(get("/api/items").param("productId", "FI-SW-01"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                // First item — flat fields
                .andExpect(jsonPath("$[0].itemId").value("EST-1"))
                .andExpect(jsonPath("$[0].productId").value("FI-SW-01"))
                .andExpect(jsonPath("$[0].listPrice").value(16.50))
                .andExpect(jsonPath("$[0].unitCost").value(10.00))
                .andExpect(jsonPath("$[0].supplierId").value(1))
                .andExpect(jsonPath("$[0].status").value("P"))
                .andExpect(jsonPath("$[0].attribute1").value("Large"))
                // First item — nested ProductDTO
                .andExpect(jsonPath("$[0].product.productId").value("FI-SW-01"))
                .andExpect(jsonPath("$[0].product.categoryId").value("FISH"))
                // First item — quantity from InventoryService (NOT from Item entity)
                .andExpect(jsonPath("$[0].quantity").value(10000))
                // Second item
                .andExpect(jsonPath("$[1].itemId").value("EST-2"))
                .andExpect(jsonPath("$[1].quantity").value(5000));

        // Verify service delegation
        verify(catalogService).getItemListByProduct("FI-SW-01");
        verify(inventoryService).getInventoryQuantity("EST-1");
        verify(inventoryService).getInventoryQuantity("EST-2");
    }

    // -----------------------------------------------------------------------
    // GET /api/items/{id} — Get single item by ID
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code GET /api/items/EST-1} returns 200 OK with a single
     * {@code ItemDTO} including nested {@code ProductDTO} and quantity.
     *
     * <p>Replaces monolith's {@code CatalogActionBean.viewItem()} (lines 179-182):
     * {@code item = catalogService.getItem(itemId); product = item.getProduct();}
     */
    @Test
    void shouldReturnItemById() throws Exception {
        // Arrange
        Item item = createItem("EST-1", "FI-SW-01", "FISH", 1,
                new BigDecimal("16.50"), new BigDecimal("10.00"), "P", "Large");

        when(catalogService.getItem("EST-1")).thenReturn(item);
        when(inventoryService.getInventoryQuantity("EST-1")).thenReturn(10000);

        // Act & Assert
        mockMvc.perform(get("/api/items/{id}", "EST-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.itemId").value("EST-1"))
                .andExpect(jsonPath("$.productId").value("FI-SW-01"))
                .andExpect(jsonPath("$.listPrice").value(16.50))
                .andExpect(jsonPath("$.unitCost").value(10.00))
                .andExpect(jsonPath("$.supplierId").value(1))
                .andExpect(jsonPath("$.status").value("P"))
                .andExpect(jsonPath("$.attribute1").value("Large"))
                // Nested ProductDTO
                .andExpect(jsonPath("$.product").isNotEmpty())
                .andExpect(jsonPath("$.product.productId").value("FI-SW-01"))
                .andExpect(jsonPath("$.product.categoryId").value("FISH"))
                .andExpect(jsonPath("$.product.name").value("Test Product"))
                .andExpect(jsonPath("$.product.description").value("Test Description"))
                // Quantity from InventoryService (NOT from Item entity)
                .andExpect(jsonPath("$.quantity").value(10000));

        // Verify service delegation
        verify(catalogService).getItem("EST-1");
        verify(inventoryService).getInventoryQuantity("EST-1");
    }

    /**
     * Verifies that {@code GET /api/items/NONEXISTENT} returns 404 Not Found when
     * the service returns null. Inventory is NOT checked when the item does not exist.
     */
    @Test
    void shouldReturn404WhenItemNotFound() throws Exception {
        // Arrange
        when(catalogService.getItem("NONEXISTENT")).thenReturn(null);

        // Act & Assert
        mockMvc.perform(get("/api/items/{id}", "NONEXISTENT"))
                .andExpect(status().isNotFound());

        // Verify — item lookup was attempted, inventory was NOT
        verify(catalogService).getItem("NONEXISTENT");
        verify(inventoryService, never()).getInventoryQuantity(anyString());
    }

    // -----------------------------------------------------------------------
    // GET /api/items/{id}/inventory — Stock check
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code GET /api/items/EST-1/inventory} returns 200 OK with
     * quantity and {@code inStock=true} when stock is available.
     *
     * <p>Replaces monolith's {@code CatalogService.isItemInStock()} (lines 87-89):
     * {@code return itemMapper.getInventoryQuantity(itemId) > 0}
     */
    @Test
    void shouldReturnInventoryForItem() throws Exception {
        // Arrange
        when(inventoryService.getInventoryQuantity("EST-1")).thenReturn(10000);

        // Act & Assert
        mockMvc.perform(get("/api/items/{id}/inventory", "EST-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.itemId").value("EST-1"))
                .andExpect(jsonPath("$.quantity").value(10000))
                .andExpect(jsonPath("$.inStock").value(true));

        // Verify
        verify(inventoryService).getInventoryQuantity("EST-1");
    }

    /**
     * Verifies that {@code GET /api/items/EST-1/inventory} returns {@code inStock=false}
     * when quantity is 0. Returns 200 OK — out of stock is not the same as not found.
     *
     * <p>Mirrors monolith's {@code CatalogServiceTest.shouldReturnFalseWhenNotExistStock()}
     * (lines 178-191) which verified {@code isItemInStock()} returns false when quantity is 0.
     */
    @Test
    void shouldReturnInventoryOutOfStock() throws Exception {
        // Arrange
        when(inventoryService.getInventoryQuantity("EST-1")).thenReturn(0);

        // Act & Assert
        mockMvc.perform(get("/api/items/{id}/inventory", "EST-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("EST-1"))
                .andExpect(jsonPath("$.quantity").value(0))
                .andExpect(jsonPath("$.inStock").value(false));

        // Verify
        verify(inventoryService).getInventoryQuantity("EST-1");
    }

    // -----------------------------------------------------------------------
    // POST /api/items/{id}/inventory/decrement — Saga inventory decrement
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code POST /api/items/EST-1/inventory/decrement} with a valid body
     * returns 200 OK with SUCCESS status on successful decrement.
     *
     * <p>Replaces monolith's {@code OrderService.insertOrder()} lines 62-68 inventory
     * decrement loop. In the decomposed architecture, Order Service calls this endpoint
     * via REST during Saga Step 2 (per AAP Section 0.7.1):
     * "Step 2: POST catalog-service/items/{id}/inventory/decrement for each line item"
     */
    @Test
    void shouldDecrementInventorySuccessfully() throws Exception {
        // Arrange — orderId serves as the Saga idempotency key
        when(inventoryService.decrementInventory(eq("EST-1"), eq(2), eq("order-123")))
                .thenReturn(true);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("quantity", 2);
        requestBody.put("orderId", "order-123");

        // Act & Assert
        mockMvc.perform(post("/api/items/{id}/inventory/decrement", "EST-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJsonString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value("EST-1"))
                .andExpect(jsonPath("$.decremented").value(2))
                .andExpect(jsonPath("$.orderId").value("order-123"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // Verify — service called with exact parameters
        verify(inventoryService).decrementInventory(eq("EST-1"), eq(2), eq("order-123"));
    }

    /**
     * Verifies that {@code POST /api/items/EST-1/inventory/decrement} returns
     * 409 Conflict when stock is insufficient.
     *
     * <p>Per AAP Section 0.7.1: "Reservation fails (insufficient stock)" → "409 Conflict"
     */
    @Test
    void shouldReturn409WhenInsufficientStock() throws Exception {
        // Arrange
        when(inventoryService.decrementInventory("EST-1", 99999, "order-456"))
                .thenReturn(false);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("quantity", 99999);
        requestBody.put("orderId", "order-456");

        // Act & Assert
        mockMvc.perform(post("/api/items/{id}/inventory/decrement", "EST-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJsonString(requestBody)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.itemId").value("EST-1"))
                .andExpect(jsonPath("$.requestedDecrement").value(99999))
                .andExpect(jsonPath("$.orderId").value("order-456"))
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_STOCK"));

        // Verify — service was called despite insufficient stock
        verify(inventoryService).decrementInventory("EST-1", 99999, "order-456");
    }

    /**
     * Verifies that {@code POST} with missing {@code orderId} returns 400 Bad Request.
     * {@code @Valid} on {@code @RequestBody InventoryDecrementRequest} triggers Jakarta
     * Bean Validation, and {@code @NotBlank} on {@code orderId} rejects null/missing values.
     * The service must NOT be called on validation failure.
     */
    @Test
    void shouldReturn400ForInvalidDecrementRequest() throws Exception {
        // Arrange — missing orderId field
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("quantity", 2);
        // orderId deliberately omitted

        // Act & Assert
        mockMvc.perform(post("/api/items/{id}/inventory/decrement", "EST-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJsonString(requestBody)))
                .andExpect(status().isBadRequest());

        // Verify — service NOT called due to validation failure
        verify(inventoryService, never()).decrementInventory(anyString(), anyInt(), anyString());
    }

    /**
     * Verifies that {@code POST} with {@code quantity=0} returns 400 Bad Request.
     * {@code @Min(1)} on the {@code quantity} field rejects zero values because the
     * monolith always decrements by the cart item quantity, which is always &ge; 1.
     */
    @Test
    void shouldReturn400ForZeroQuantityDecrement() throws Exception {
        // Arrange
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("quantity", 0);
        requestBody.put("orderId", "order-789");

        // Act & Assert
        mockMvc.perform(post("/api/items/{id}/inventory/decrement", "EST-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJsonString(requestBody)))
                .andExpect(status().isBadRequest());

        // Verify — service NOT called due to validation failure
        verify(inventoryService, never()).decrementInventory(anyString(), anyInt(), anyString());
    }

    /**
     * Verifies that {@code POST} with {@code quantity=-1} returns 400 Bad Request.
     * Negative quantity is also rejected by {@code @Min(1)} validation, ensuring
     * inventory can never be inadvertently incremented via this endpoint.
     */
    @Test
    void shouldReturn400ForNegativeQuantityDecrement() throws Exception {
        // Arrange
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("quantity", -1);
        requestBody.put("orderId", "order-neg");

        // Act & Assert
        mockMvc.perform(post("/api/items/{id}/inventory/decrement", "EST-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(asJsonString(requestBody)))
                .andExpect(status().isBadRequest());

        // Verify — service NOT called due to validation failure
        verify(inventoryService, never()).decrementInventory(anyString(), anyInt(), anyString());
    }

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Factory method to create an {@link Item} entity with nested {@link Product}
     * (which has nested {@link Category}) and nested {@link Supplier} for test data
     * construction.
     *
     * <p>Mirrors the JPA entity relationship structure:
     * {@code Item → Product → Category}, {@code Item → Supplier}.
     *
     * <p>Does NOT set {@code quantity} — that field lives on the {@link
     * com.jpetstore.catalog.entity.Inventory Inventory} entity, separate from Item
     * in the decomposed architecture. Quantity is populated by
     * {@link InventoryService#getInventoryQuantity(String)} in the controller's
     * {@code toDTO()} mapping.
     *
     * @param itemId    unique item identifier (e.g., "EST-1")
     * @param productId parent product identifier (e.g., "FI-SW-01")
     * @param categoryId product's category identifier (e.g., "FISH")
     * @param supplierId supplier identifier (e.g., 1)
     * @param listPrice item list price
     * @param unitCost  item unit cost
     * @param status    item status (e.g., "P" for available)
     * @param attr1     first attribute (e.g., "Large", "Small")
     * @return a fully populated Item entity with nested Product, Category, and Supplier
     */
    private Item createItem(String itemId, String productId, String categoryId,
                            int supplierId, BigDecimal listPrice, BigDecimal unitCost,
                            String status, String attr1) {
        Category category = new Category();
        category.setCatId(categoryId);

        Product product = new Product();
        product.setProductId(productId);
        product.setCategory(category);
        product.setName("Test Product");
        product.setDescription("Test Description");

        Supplier supplier = new Supplier();
        supplier.setSuppId(supplierId);

        Item item = new Item();
        item.setItemId(itemId);
        item.setProduct(product);
        item.setSupplier(supplier);
        item.setListPrice(listPrice);
        item.setUnitCost(unitCost);
        item.setStatus(status);
        item.setAttribute1(attr1);
        return item;
    }

    /**
     * Serializes an object to a JSON string using Jackson {@link ObjectMapper}.
     * Used for constructing POST request bodies in inventory decrement tests.
     *
     * @param obj the object to serialize (typically a {@link Map})
     * @return JSON string representation
     * @throws Exception if serialization fails
     */
    private String asJsonString(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }
}
