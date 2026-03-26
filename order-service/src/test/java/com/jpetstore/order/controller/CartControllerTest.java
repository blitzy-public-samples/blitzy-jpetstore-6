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
package com.jpetstore.order.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpetstore.order.config.SecurityConfig;
import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.dto.CartItemDTO;
import com.jpetstore.order.exception.ResourceNotFoundException;
import com.jpetstore.order.service.CartStateService;

/**
 * {@code @WebMvcTest} unit tests for {@link CartController} — the externalized
 * cart REST API in the Order Service.
 *
 * <p>Validates all 5 REST endpoints (GET cart, POST add item, DELETE remove item,
 * PUT update quantities, DELETE clear cart) covering happy-path and error scenarios
 * including Jakarta Bean Validation on {@link CartController.AddItemRequest}
 * ({@code @NotBlank itemId}) and JSON response structure via {@code jsonPath}
 * assertions.</p>
 *
 * <p>Uses {@code @WebMvcTest(CartController.class)} to load only the web layer
 * (no Redis, no JPA, no full application context). {@link CartStateService} is
 * mocked via {@code @MockitoBean}.</p>
 *
 * <h3>Monolith CartActionBeanTest mapping:</h3>
 * <ul>
 *   <li>{@code addItemToCart_WithNullWorkingItemId_ShouldReturnError}
 *       → {@link #addItemToCart_WithNullItemId_Returns400BadRequest()}</li>
 *   <li>{@code addItemToCart_WithEmptyWorkingItemId_ShouldReturnError}
 *       → {@link #addItemToCart_WithBlankItemId_Returns400BadRequest()}</li>
 *   <li>{@code addItemToCart_WithBlankWorkingItemId_ShouldReturnError}
 *       → {@link #addItemToCart_WithWhitespaceItemId_Returns400BadRequest()}</li>
 *   <li>{@code removeItemFromCart_WithNonExistentItem_ShouldReturnError}
 *       → {@link #removeItemFromCart_WithNonExistentItem_Returns404OrError()}</li>
 *   <li>{@code clearShouldResetCartAndWorkingItemId}
 *       → {@link #clearCart_Returns204NoContent()}</li>
 * </ul>
 *
 * @see CartController
 * @see CartStateService
 * @see CartDTO
 * @see CartItemDTO
 */
@WebMvcTest(CartController.class)
@Import(SecurityConfig.class)
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CartStateService cartStateService;

    // -----------------------------------------------------------------------
    // Test data helpers — construct CartDTO and CartItemDTO instances
    // with realistic monolith-derived values (EST-1 Angelfish $16.50,
    // EST-14 Great Dane $58.50).
    // -----------------------------------------------------------------------

    /**
     * Creates a fully-populated {@link CartDTO} with two items reflecting
     * monolith seed data prices for EST-1 (Angelfish, $16.50) and
     * EST-14 (Great Dane, $58.50).
     *
     * @return CartDTO with 2 items, subTotal=$91.50, numberOfItems=2
     */
    private CartDTO createSampleCartDTO() {
        CartItemDTO item1 = new CartItemDTO();
        item1.setItemId("EST-1");
        item1.setQuantity(2);
        item1.setInStock(true);
        item1.setUnitPrice(new BigDecimal("16.50"));
        item1.setTotal(new BigDecimal("33.00"));

        CartItemDTO item2 = new CartItemDTO();
        item2.setItemId("EST-14");
        item2.setQuantity(1);
        item2.setInStock(true);
        item2.setUnitPrice(new BigDecimal("58.50"));
        item2.setTotal(new BigDecimal("58.50"));

        CartDTO cart = new CartDTO();
        cart.setId("session-abc-123");
        cart.setItems(Arrays.asList(item1, item2));
        cart.setSubTotal(new BigDecimal("91.50"));
        cart.setNumberOfItems(2);
        return cart;
    }

    /**
     * Creates an empty {@link CartDTO} for the given session — mirrors a
     * newly-created cart (monolith: {@code new Cart()} has zero items,
     * subTotal=0).
     *
     * @param sessionId the cart session identifier
     * @return CartDTO with empty items list, subTotal=ZERO, numberOfItems=0
     */
    private CartDTO createEmptyCartDTO(String sessionId) {
        CartDTO cart = new CartDTO();
        cart.setId(sessionId);
        cart.setItems(Collections.emptyList());
        cart.setSubTotal(BigDecimal.ZERO);
        cart.setNumberOfItems(0);
        return cart;
    }

    // ===================================================================
    // GET /api/cart/{sessionId} — Retrieve cart state
    // ===================================================================

    /**
     * Verifies that {@code GET /api/cart/{sessionId}} returns 200 OK with
     * a populated CartDTO when the session already has items in the cart.
     *
     * <p>Validates JSON response structure: id, items array with correct
     * item fields (itemId, quantity, inStock, unitPrice, total), subTotal,
     * and numberOfItems.</p>
     */
    @Test
    @DisplayName("GET cart — existing session returns 200 with populated CartDTO")
    void getCart_WithExistingSession_Returns200WithCartDTO() throws Exception {
        // Given: a session with 2 items in the cart
        CartDTO sampleCart = createSampleCartDTO();
        when(cartStateService.getCart("session-abc-123")).thenReturn(sampleCart);

        // When & Then: GET request returns full cart data
        mockMvc.perform(get("/api/cart/{sessionId}", "session-abc-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("session-abc-123")))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].itemId", is("EST-1")))
                .andExpect(jsonPath("$.items[0].quantity", is(2)))
                .andExpect(jsonPath("$.items[0].inStock", is(true)))
                .andExpect(jsonPath("$.items[0].unitPrice", is(16.50)))
                .andExpect(jsonPath("$.items[0].total", is(33.00)))
                .andExpect(jsonPath("$.items[1].itemId", is("EST-14")))
                .andExpect(jsonPath("$.items[1].quantity", is(1)))
                .andExpect(jsonPath("$.items[1].unitPrice", is(58.50)))
                .andExpect(jsonPath("$.items[1].total", is(58.50)))
                .andExpect(jsonPath("$.subTotal", is(91.50)))
                .andExpect(jsonPath("$.numberOfItems", is(2)));

        verify(cartStateService).getCart("session-abc-123");
    }

    /**
     * Verifies that {@code GET /api/cart/{sessionId}} returns 200 OK with
     * an empty CartDTO when the session has no items (new session).
     *
     * <p>Maps to monolith CartActionBeanTest: constructor creates empty cart
     * with zero items (CartActionBeanTest.java line 48–54).</p>
     */
    @Test
    @DisplayName("GET cart — new session returns 200 with empty CartDTO")
    void getCart_WithNewSession_Returns200WithEmptyCart() throws Exception {
        // Given: a new session has no items in cart
        CartDTO emptyCart = createEmptyCartDTO("new-session");
        when(cartStateService.getCart("new-session")).thenReturn(emptyCart);

        // When & Then: GET request returns empty cart
        mockMvc.perform(get("/api/cart/{sessionId}", "new-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("new-session")))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.subTotal", is(0)))
                .andExpect(jsonPath("$.numberOfItems", is(0)));

        verify(cartStateService).getCart("new-session");
    }

    // ===================================================================
    // POST /api/cart/{sessionId}/items — Add item to cart
    // ===================================================================

    /**
     * Verifies that {@code POST /api/cart/{sessionId}/items} with a valid
     * {@link CartController.AddItemRequest} (non-blank itemId) returns
     * 200 OK with the updated cart containing the newly added item.
     *
     * <p>Uses {@link CartController.AddItemRequest} static inner class
     * explicitly, serialized to JSON via ObjectMapper.</p>
     */
    @Test
    @DisplayName("POST add item — valid itemId returns 200 with updated cart")
    void addItemToCart_WithValidItemId_Returns200WithUpdatedCart() throws Exception {
        // Given: a valid AddItemRequest with itemId=EST-1
        CartController.AddItemRequest request = new CartController.AddItemRequest();
        request.setItemId("EST-1");

        CartItemDTO addedItem = new CartItemDTO();
        addedItem.setItemId("EST-1");
        addedItem.setQuantity(1);
        addedItem.setInStock(true);
        addedItem.setUnitPrice(new BigDecimal("16.50"));
        addedItem.setTotal(new BigDecimal("16.50"));

        CartDTO updatedCart = new CartDTO();
        updatedCart.setId("session-abc-123");
        updatedCart.setItems(List.of(addedItem));
        updatedCart.setSubTotal(new BigDecimal("16.50"));
        updatedCart.setNumberOfItems(1);

        when(cartStateService.addItem("session-abc-123", "EST-1"))
                .thenReturn(updatedCart);

        // When & Then: POST with valid request body returns updated cart
        mockMvc.perform(post("/api/cart/{sessionId}/items", "session-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("session-abc-123")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].itemId", is("EST-1")))
                .andExpect(jsonPath("$.items[0].quantity", is(1)))
                .andExpect(jsonPath("$.subTotal", is(16.50)))
                .andExpect(jsonPath("$.numberOfItems", is(1)));

        verify(cartStateService).addItem("session-abc-123", "EST-1");
    }

    /**
     * Verifies that {@code POST /api/cart/{sessionId}/items} with a blank
     * (empty string) itemId returns 400 Bad Request due to
     * {@code @NotBlank} validation on {@link CartController.AddItemRequest#getItemId()}.
     *
     * <p>Maps to monolith: {@code addItemToCart_WithEmptyWorkingItemId_ShouldReturnError}
     * (CartActionBeanTest.java line 74–81) — monolith returned Error.jsp;
     * microservice returns 400.</p>
     */
    @Test
    @DisplayName("POST add item — blank itemId returns 400 Bad Request")
    void addItemToCart_WithBlankItemId_Returns400BadRequest() throws Exception {
        // Given: request body with blank itemId violates @NotBlank
        String requestBody = objectMapper.writeValueAsString(
                Map.of("itemId", ""));

        // When & Then: @Valid triggers MethodArgumentNotValidException → 400
        mockMvc.perform(post("/api/cart/{sessionId}/items", "session-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        // Service must never be called when validation fails
        verify(cartStateService, never()).addItem(anyString(), anyString());
    }

    /**
     * Verifies that {@code POST /api/cart/{sessionId}/items} with a null
     * itemId (sent as empty JSON body {@code {}}) returns 400 Bad Request
     * due to {@code @NotBlank} validation.
     *
     * <p>Maps to monolith: {@code addItemToCart_WithNullWorkingItemId_ShouldReturnError}
     * (CartActionBeanTest.java line 64–71) — monolith returned Error.jsp;
     * microservice returns 400.</p>
     */
    @Test
    @DisplayName("POST add item — null/missing itemId returns 400 Bad Request")
    void addItemToCart_WithNullItemId_Returns400BadRequest() throws Exception {
        // Given: request body with no itemId field — triggers @NotBlank on null
        String requestBody = "{}";

        // When & Then: @Valid triggers MethodArgumentNotValidException → 400
        mockMvc.perform(post("/api/cart/{sessionId}/items", "session-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        // Service must never be called when validation fails
        verify(cartStateService, never()).addItem(anyString(), anyString());
    }

    /**
     * Verifies that {@code POST /api/cart/{sessionId}/items} with a
     * whitespace-only itemId returns 400 Bad Request due to
     * {@code @NotBlank} validation (whitespace-only strings fail @NotBlank).
     *
     * <p>Maps to monolith: {@code addItemToCart_WithBlankWorkingItemId_ShouldReturnError}
     * (CartActionBeanTest.java line 84–91) — monolith returned Error.jsp;
     * microservice returns 400.</p>
     */
    @Test
    @DisplayName("POST add item — whitespace-only itemId returns 400 Bad Request")
    void addItemToCart_WithWhitespaceItemId_Returns400BadRequest() throws Exception {
        // Given: request body with whitespace-only itemId violates @NotBlank
        String requestBody = objectMapper.writeValueAsString(
                Map.of("itemId", "   "));

        // When & Then: @Valid triggers MethodArgumentNotValidException → 400
        mockMvc.perform(post("/api/cart/{sessionId}/items", "session-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        // Service must never be called when validation fails
        verify(cartStateService, never()).addItem(anyString(), anyString());
    }

    // ===================================================================
    // DELETE /api/cart/{sessionId}/items/{itemId} — Remove item from cart
    // ===================================================================

    /**
     * Verifies that {@code DELETE /api/cart/{sessionId}/items/{itemId}} with
     * an existing item returns 200 OK with the updated cart (item removed).
     */
    @Test
    @DisplayName("DELETE remove item — existing item returns 200 with updated cart")
    void removeItemFromCart_WithExistingItem_Returns200() throws Exception {
        // Given: removing EST-1 leaves only EST-14 in cart
        CartItemDTO remainingItem = new CartItemDTO();
        remainingItem.setItemId("EST-14");
        remainingItem.setQuantity(1);
        remainingItem.setInStock(true);
        remainingItem.setUnitPrice(new BigDecimal("58.50"));
        remainingItem.setTotal(new BigDecimal("58.50"));

        CartDTO updatedCart = new CartDTO();
        updatedCart.setId("session-abc-123");
        updatedCart.setItems(List.of(remainingItem));
        updatedCart.setSubTotal(new BigDecimal("58.50"));
        updatedCart.setNumberOfItems(1);

        when(cartStateService.removeItemById("session-abc-123", "EST-1"))
                .thenReturn(updatedCart);

        // When & Then: DELETE request returns cart without the removed item
        mockMvc.perform(delete("/api/cart/{sessionId}/items/{itemId}",
                        "session-abc-123", "EST-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("session-abc-123")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].itemId", is("EST-14")))
                .andExpect(jsonPath("$.subTotal", is(58.50)))
                .andExpect(jsonPath("$.numberOfItems", is(1)));

        verify(cartStateService).removeItemById("session-abc-123", "EST-1");
    }

    /**
     * Verifies that {@code DELETE /api/cart/{sessionId}/items/{itemId}} with
     * a non-existent item returns 404 Not Found (or an error status) when
     * {@link CartStateService#removeItemById(String, String)} throws
     * {@link ResourceNotFoundException}.
     *
     * <p>Maps to monolith: {@code removeItemFromCart_WithNonExistentItem_ShouldReturnError}
     * (CartActionBeanTest.java line 124–131) — monolith's {@code cart.removeItemById()}
     * returned null → Error.jsp. In the microservice, the service throws
     * {@code ResourceNotFoundException} → controller's {@code @ExceptionHandler}
     * returns 404.</p>
     */
    @Test
    @DisplayName("DELETE remove item — non-existent item returns 404 Not Found")
    void removeItemFromCart_WithNonExistentItem_Returns404OrError() throws Exception {
        // Given: service throws ResourceNotFoundException for item not in cart
        when(cartStateService.removeItemById(eq("session-abc-123"), eq("NON-EXISTENT")))
                .thenThrow(new ResourceNotFoundException(
                        "Item NON-EXISTENT not in cart"));

        // When & Then: controller's @ExceptionHandler maps to 404
        mockMvc.perform(delete("/api/cart/{sessionId}/items/{itemId}",
                        "session-abc-123", "NON-EXISTENT"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("Item NON-EXISTENT not in cart")));

        verify(cartStateService).removeItemById("session-abc-123", "NON-EXISTENT");
    }

    // ===================================================================
    // PUT /api/cart/{sessionId} — Update cart quantities
    // ===================================================================

    /**
     * Verifies that {@code PUT /api/cart/{sessionId}} with a valid
     * {@code Map<String, Integer>} request body returns 200 OK with the
     * updated cart reflecting new quantities.
     *
     * <p>Test data: EST-1 quantity updated to 3 (total=$49.50),
     * EST-14 quantity updated to 2 (total=$117.00), subTotal=$166.50.</p>
     */
    @Test
    @DisplayName("PUT update quantities — valid updates returns 200 with updated cart")
    void updateCartQuantities_WithValidUpdates_Returns200() throws Exception {
        // Given: quantity update map and expected response
        Map<String, Integer> quantityUpdates = Map.of("EST-1", 3, "EST-14", 2);

        CartItemDTO item1 = new CartItemDTO();
        item1.setItemId("EST-1");
        item1.setQuantity(3);
        item1.setInStock(true);
        item1.setUnitPrice(new BigDecimal("16.50"));
        item1.setTotal(new BigDecimal("49.50"));

        CartItemDTO item2 = new CartItemDTO();
        item2.setItemId("EST-14");
        item2.setQuantity(2);
        item2.setInStock(true);
        item2.setUnitPrice(new BigDecimal("58.50"));
        item2.setTotal(new BigDecimal("117.00"));

        CartDTO updatedCart = new CartDTO();
        updatedCart.setId("session-abc-123");
        updatedCart.setItems(Arrays.asList(item1, item2));
        updatedCart.setSubTotal(new BigDecimal("166.50"));
        updatedCart.setNumberOfItems(2);

        when(cartStateService.updateQuantities(eq("session-abc-123"), any()))
                .thenReturn(updatedCart);

        // When & Then: PUT with quantity map returns updated cart
        mockMvc.perform(put("/api/cart/{sessionId}", "session-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quantityUpdates)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("session-abc-123")))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.subTotal", is(166.50)))
                .andExpect(jsonPath("$.numberOfItems", is(2)));

        verify(cartStateService).updateQuantities(eq("session-abc-123"), any());
    }

    /**
     * Verifies that {@code PUT /api/cart/{sessionId}} with an empty
     * {@code Map<String, Integer>} ({@code {}}) returns 200 OK with the
     * cart unchanged — no-op update is valid.
     */
    @Test
    @DisplayName("PUT update quantities — empty map returns 200 with unchanged cart")
    void updateCartQuantities_WithEmptyMap_Returns200() throws Exception {
        // Given: empty update map — no changes
        Map<String, Integer> emptyUpdates = Collections.emptyMap();

        CartDTO currentCart = createSampleCartDTO();
        when(cartStateService.updateQuantities(eq("session-abc-123"), anyMap()))
                .thenReturn(currentCart);

        // When & Then: PUT with empty map returns current cart unchanged
        mockMvc.perform(put("/api/cart/{sessionId}", "session-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyUpdates)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("session-abc-123")))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.subTotal", is(91.50)))
                .andExpect(jsonPath("$.numberOfItems", is(2)));

        verify(cartStateService).updateQuantities(eq("session-abc-123"), anyMap());
    }

    // ===================================================================
    // DELETE /api/cart/{sessionId} — Clear entire cart
    // ===================================================================

    /**
     * Verifies that {@code DELETE /api/cart/{sessionId}} returns
     * 204 No Content with an empty response body after clearing the cart.
     *
     * <p>Maps to monolith: {@code clearShouldResetCartAndWorkingItemId}
     * (CartActionBeanTest.java line 134–141) — monolith {@code CartActionBean.clear()}
     * resets cart to {@code new Cart()}. In the microservice: delete cart
     * from Redis → 204 No Content.</p>
     */
    @Test
    @DisplayName("DELETE clear cart — returns 204 No Content")
    void clearCart_Returns204NoContent() throws Exception {
        // Given: clearCart is a void method — use doNothing()
        doNothing().when(cartStateService).clearCart("session-abc-123");

        // When & Then: DELETE request returns 204 with empty body
        mockMvc.perform(delete("/api/cart/{sessionId}", "session-abc-123"))
                .andExpect(status().isNoContent());

        // Verify service was called to clear the cart
        verify(cartStateService).clearCart("session-abc-123");
    }
}
