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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.dto.CartItemDTO;
import com.jpetstore.order.service.CartStateService;

/**
 * MockMvc-based unit tests for {@link CartController} — the externalized cart
 * REST API in the Order Service.
 *
 * <p>Tests all 5 REST endpoints (GET, POST, DELETE item, PUT quantities,
 * DELETE cart), input validation (sessionId {@code @Pattern}, request body
 * {@code @Valid}), and delegation to {@link CartStateService}.</p>
 *
 * <p>Uses {@code @WebMvcTest} to load only the web layer (no Redis, no JPA,
 * no full application context). {@link CartStateService} is mocked via
 * {@code @MockBean}.</p>
 *
 * <h3>Coverage scope:</h3>
 * <ul>
 *   <li>Happy-path for each of the 5 endpoints</li>
 *   <li>Request body validation (blank itemId, missing body)</li>
 *   <li>SessionId format validation (path traversal, special characters)</li>
 *   <li>Correct HTTP status codes (200 OK, 204 No Content, 400 Bad Request)</li>
 *   <li>Correct JSON response structure</li>
 * </ul>
 *
 * @see CartController
 * @see CartStateService
 */
@WebMvcTest(CartController.class)
class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CartStateService cartStateService;

    // -----------------------------------------------------------------------
    // Test data helpers
    // -----------------------------------------------------------------------

    /**
     * Creates a CartDTO with one item for standard test responses.
     */
    private CartDTO createSingleItemCart(String sessionId, String itemId,
                                         int quantity, BigDecimal unitPrice) {
        CartItemDTO item = new CartItemDTO();
        item.setItemId(itemId);
        item.setQuantity(quantity);
        item.setInStock(true);
        item.setUnitPrice(unitPrice);
        item.setTotal(unitPrice.multiply(BigDecimal.valueOf(quantity)));

        CartDTO cart = new CartDTO();
        cart.setId(sessionId);
        cart.setItems(List.of(item));
        cart.setSubTotal(item.getTotal());
        cart.setNumberOfItems(1);
        return cart;
    }

    /**
     * Creates an empty CartDTO for the given session.
     */
    private CartDTO createEmptyCart(String sessionId) {
        CartDTO cart = new CartDTO();
        cart.setId(sessionId);
        cart.setItems(new ArrayList<>());
        cart.setSubTotal(BigDecimal.ZERO);
        cart.setNumberOfItems(0);
        return cart;
    }

    // -----------------------------------------------------------------------
    // GET /api/cart/{sessionId} — getCart
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("GET /api/cart/{sessionId}")
    class GetCartTests {

        @Test
        @DisplayName("Returns 200 OK with cart contents for valid sessionId")
        void getCart_validSession_returnsCartDTO() throws Exception {
            // Given
            String sessionId = "abc-123-def";
            CartDTO cartDTO = createSingleItemCart(sessionId, "EST-1", 2,
                    new BigDecimal("16.50"));
            when(cartStateService.getCart(sessionId)).thenReturn(cartDTO);

            // When & Then
            mockMvc.perform(get("/api/cart/{sessionId}", sessionId))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.id", is(sessionId)))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].itemId", is("EST-1")))
                    .andExpect(jsonPath("$.items[0].quantity", is(2)))
                    .andExpect(jsonPath("$.items[0].inStock", is(true)))
                    .andExpect(jsonPath("$.numberOfItems", is(1)));

            verify(cartStateService).getCart(sessionId);
        }

        @Test
        @DisplayName("Returns 200 OK with empty cart when no items exist")
        void getCart_emptyCart_returnsEmptyCartDTO() throws Exception {
            // Given
            String sessionId = "new-session-id";
            CartDTO emptyCart = createEmptyCart(sessionId);
            when(cartStateService.getCart(sessionId)).thenReturn(emptyCart);

            // When & Then
            mockMvc.perform(get("/api/cart/{sessionId}", sessionId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(sessionId)))
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.numberOfItems", is(0)));

            verify(cartStateService).getCart(sessionId);
        }

        @Test
        @DisplayName("Rejects sessionId with special/injection characters per @Pattern")
        void getCart_specialCharsSessionId_rejected() throws Exception {
            // When & Then: session ID with underscore and exclamation mark fails
            // @Pattern("^[a-zA-Z0-9\\-]{1,128}$") — only alphanumeric and hyphens allowed.
            // @Validated + @Pattern violation → ConstraintViolationException →
            // CartController @ExceptionHandler returns 400 Bad Request.
            mockMvc.perform(get("/api/cart/{sessionId}", "bad_session!"))
                    .andExpect(status().isBadRequest());

            // Service must never be called when the sessionId fails @Pattern validation
            verify(cartStateService, never()).getCart("bad_session!");
        }
    }

    // -----------------------------------------------------------------------
    // POST /api/cart/{sessionId}/items — addItemToCart
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("POST /api/cart/{sessionId}/items")
    class AddItemToCartTests {

        @Test
        @DisplayName("Returns 200 OK with updated cart after adding item")
        void addItemToCart_validRequest_returnsUpdatedCart() throws Exception {
            // Given
            String sessionId = "session-001";
            String itemId = "EST-14";
            CartDTO updatedCart = createSingleItemCart(sessionId, itemId, 1,
                    new BigDecimal("58.50"));
            when(cartStateService.addItem(sessionId, itemId)).thenReturn(updatedCart);

            String requestBody = objectMapper.writeValueAsString(
                    Map.of("itemId", itemId));

            // When & Then
            mockMvc.perform(post("/api/cart/{sessionId}/items", sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(sessionId)))
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].itemId", is(itemId)))
                    .andExpect(jsonPath("$.items[0].quantity", is(1)));

            verify(cartStateService).addItem(sessionId, itemId);
        }

        @Test
        @DisplayName("Returns 400 Bad Request when itemId is blank")
        void addItemToCart_blankItemId_returns400() throws Exception {
            // Given: request body with blank itemId triggers @NotBlank validation
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("itemId", ""));

            // When & Then
            mockMvc.perform(post("/api/cart/{sessionId}/items", "session-001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(cartStateService, never()).addItem(anyString(), anyString());
        }

        @Test
        @DisplayName("Returns 400 Bad Request when itemId is missing from body")
        void addItemToCart_missingItemId_returns400() throws Exception {
            // Given: request body with null itemId triggers @NotBlank validation
            String requestBody = "{}";

            // When & Then
            mockMvc.perform(post("/api/cart/{sessionId}/items", "session-001")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(cartStateService, never()).addItem(anyString(), anyString());
        }

        @Test
        @DisplayName("Returns 400 Bad Request when request body is missing")
        void addItemToCart_noRequestBody_returns400() throws Exception {
            // When & Then: no body at all
            mockMvc.perform(post("/api/cart/{sessionId}/items", "session-001")
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());

            verify(cartStateService, never()).addItem(anyString(), anyString());
        }

        @Test
        @DisplayName("Rejects sessionId with special characters on addItem")
        void addItemToCart_invalidSessionId_rejected() throws Exception {
            // Given
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("itemId", "EST-1"));

            // When & Then: session ID with underscore + special chars fails @Pattern
            // CartController @ExceptionHandler converts ConstraintViolationException to 400.
            mockMvc.perform(post("/api/cart/{sessionId}/items", "bad_session!")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(cartStateService, never()).addItem(anyString(), anyString());
        }
    }

    // -----------------------------------------------------------------------
    // DELETE /api/cart/{sessionId}/items/{itemId} — removeItemFromCart
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DELETE /api/cart/{sessionId}/items/{itemId}")
    class RemoveItemFromCartTests {

        @Test
        @DisplayName("Returns 200 OK with updated cart after item removal")
        void removeItemFromCart_validRequest_returnsUpdatedCart() throws Exception {
            // Given
            String sessionId = "session-002";
            String itemId = "EST-1";
            CartDTO updatedCart = createEmptyCart(sessionId);
            when(cartStateService.removeItemById(sessionId, itemId))
                    .thenReturn(updatedCart);

            // When & Then
            mockMvc.perform(delete("/api/cart/{sessionId}/items/{itemId}",
                            sessionId, itemId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(sessionId)))
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.numberOfItems", is(0)));

            verify(cartStateService).removeItemById(sessionId, itemId);
        }

        @Test
        @DisplayName("Rejects sessionId with special characters on removeItem")
        void removeItemFromCart_invalidSessionId_rejected() throws Exception {
            // When & Then: sessionId with dot/underscore fails @Pattern
            // CartController @ExceptionHandler converts ConstraintViolationException to 400.
            mockMvc.perform(delete("/api/cart/{sessionId}/items/{itemId}",
                            "session.invalid_id", "EST-1"))
                    .andExpect(status().isBadRequest());

            verify(cartStateService, never()).removeItemById(anyString(), anyString());
        }
    }

    // -----------------------------------------------------------------------
    // PUT /api/cart/{sessionId} — updateCartQuantities
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("PUT /api/cart/{sessionId}")
    class UpdateCartQuantitiesTests {

        @Test
        @DisplayName("Returns 200 OK with updated cart after quantity updates")
        void updateCartQuantities_validRequest_returnsUpdatedCart() throws Exception {
            // Given
            String sessionId = "session-003";
            Map<String, Integer> quantityUpdates = new HashMap<>();
            quantityUpdates.put("EST-1", 3);
            quantityUpdates.put("EST-14", 2);

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
            updatedCart.setId(sessionId);
            updatedCart.setItems(List.of(item1, item2));
            updatedCart.setSubTotal(new BigDecimal("166.50"));
            updatedCart.setNumberOfItems(2);

            when(cartStateService.updateQuantities(eq(sessionId), anyMap()))
                    .thenReturn(updatedCart);

            String requestBody = objectMapper.writeValueAsString(quantityUpdates);

            // When & Then
            mockMvc.perform(put("/api/cart/{sessionId}", sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(sessionId)))
                    .andExpect(jsonPath("$.items", hasSize(2)))
                    .andExpect(jsonPath("$.numberOfItems", is(2)));

            verify(cartStateService).updateQuantities(eq(sessionId), anyMap());
        }

        @Test
        @DisplayName("Handles quantity < 1 (item removal) in update request")
        void updateCartQuantities_quantityBelowOne_handledByService() throws Exception {
            // Given: quantity=0 means "remove this item" per monolith behavior
            String sessionId = "session-003";
            Map<String, Integer> quantityUpdates = Map.of("EST-1", 0);

            CartDTO updatedCart = createEmptyCart(sessionId);
            when(cartStateService.updateQuantities(eq(sessionId), anyMap()))
                    .thenReturn(updatedCart);

            String requestBody = objectMapper.writeValueAsString(quantityUpdates);

            // When & Then
            mockMvc.perform(put("/api/cart/{sessionId}", sessionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)));

            verify(cartStateService).updateQuantities(eq(sessionId), anyMap());
        }

        @Test
        @DisplayName("Rejects sessionId with special characters on updateQuantities")
        void updateCartQuantities_invalidSessionId_rejected() throws Exception {
            // Given
            String requestBody = objectMapper.writeValueAsString(
                    Map.of("EST-1", 2));

            // When & Then: session ID with underscore and exclamation mark fails @Pattern
            // CartController @ExceptionHandler converts ConstraintViolationException to 400.
            mockMvc.perform(put("/api/cart/{sessionId}", "session_bad!")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(cartStateService, never()).updateQuantities(anyString(), anyMap());
        }
    }

    // -----------------------------------------------------------------------
    // DELETE /api/cart/{sessionId} — clearCart
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("DELETE /api/cart/{sessionId}")
    class ClearCartTests {

        @Test
        @DisplayName("Returns 204 No Content after clearing cart")
        void clearCart_validSession_returns204() throws Exception {
            // Given
            String sessionId = "session-004";

            // When & Then
            mockMvc.perform(delete("/api/cart/{sessionId}", sessionId))
                    .andExpect(status().isNoContent());

            verify(cartStateService).clearCart(sessionId);
        }

        @Test
        @DisplayName("Rejects sessionId with special characters on clearCart")
        void clearCart_invalidSessionId_rejected() throws Exception {
            // When & Then: sessionId with underscore fails @Pattern
            // CartController @ExceptionHandler converts ConstraintViolationException to 400.
            mockMvc.perform(delete("/api/cart/{sessionId}", "admin_bad!"))
                    .andExpect(status().isBadRequest());

            verify(cartStateService, never()).clearCart(anyString());
        }
    }
}
