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

import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.validation.annotation.Validated;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.exception.ResourceNotFoundException;
import com.jpetstore.order.service.CartStateService;

/**
 * REST controller for externalized cart state management in the Order Service.
 *
 * <p>Replaces the monolith's session-scoped {@code CartActionBean} (Stripes
 * {@code @SessionScope} ActionBean) with a stateless REST API backed by
 * Redis-externalized cart state.</p>
 *
 * <h3>Architectural Difference from Monolith</h3>
 * <ul>
 *   <li><strong>Monolith:</strong> Cart state is a {@code @SessionScope} object stored
 *       in-memory via {@code Collections.synchronizedMap(new HashMap<>())} and an
 *       {@code ArrayList<CartItem>}, tied to the HTTP session.</li>
 *   <li><strong>Microservice:</strong> Cart state is externalized to Redis, keyed by
 *       {@code sessionId} (anonymous users) or {@code username} (authenticated users).
 *       This controller is completely stateless — all cart business logic is delegated
 *       to {@link CartStateService}.</li>
 * </ul>
 *
 * <h3>REST Endpoint Mapping from Monolith</h3>
 * <table>
 *   <tr><th>Monolith Method</th><th>REST Endpoint</th></tr>
 *   <tr><td>{@code CartActionBean.viewCart()}</td>
 *       <td>{@code GET /api/cart/{sessionId}}</td></tr>
 *   <tr><td>{@code CartActionBean.addItemToCart()}</td>
 *       <td>{@code POST /api/cart/{sessionId}/items}</td></tr>
 *   <tr><td>{@code CartActionBean.removeItemFromCart()}</td>
 *       <td>{@code DELETE /api/cart/{sessionId}/items/{itemId}}</td></tr>
 *   <tr><td>{@code CartActionBean.updateCartQuantities()}</td>
 *       <td>{@code PUT /api/cart/{sessionId}}</td></tr>
 *   <tr><td>{@code CartActionBean.clear()}</td>
 *       <td>{@code DELETE /api/cart/{sessionId}}</td></tr>
 * </table>
 *
 * <h3>Design Principles</h3>
 * <ul>
 *   <li>This controller is a <strong>thin HTTP adapter</strong> — it only maps HTTP
 *       requests to {@link CartStateService} method calls. Zero business logic resides
 *       in the controller.</li>
 *   <li>All cart business logic (add, remove, increment, update quantity, compute
 *       subtotal, clear) lives in {@link CartStateService}.</li>
 *   <li>No monolith domain classes ({@code Cart}, {@code CartItem}, {@code Item}) are
 *       referenced — those are fully replaced by the microservice's DTO and entity layer.</li>
 *   <li>No in-memory state management — no {@code Collections.synchronizedMap},
 *       no {@code ArrayList<CartItem>}. All state is in Redis.</li>
 * </ul>
 *
 * @see CartStateService
 * @see CartDTO
 */
@RestController
@RequestMapping("/api/cart")
@Validated
public class CartController {

    private static final Logger log = LoggerFactory.getLogger(CartController.class);

    private final CartStateService cartStateService;

    /**
     * Constructs a new {@code CartController} with the required dependency.
     *
     * <p>Uses Spring Boot constructor injection (no {@code @Autowired} needed
     * for single-constructor classes). The {@link CartStateService} handles all
     * cart business logic and Redis persistence.</p>
     *
     * @param cartStateService the Redis-backed cart state management service
     */
    public CartController(CartStateService cartStateService) {
        this.cartStateService = cartStateService;
    }

    // -----------------------------------------------------------------------
    // Exception Handling
    // -----------------------------------------------------------------------

    /**
     * Handles {@link ConstraintViolationException} thrown when {@code @Pattern}
     * validation on path variables fails.
     *
     * <p>Without this handler, Spring's {@code @Validated} AOP proxy throws a
     * {@code ConstraintViolationException} that propagates as a raw
     * {@code ServletException} (HTTP 500) instead of a proper 400 Bad Request.
     * This handler converts it to a structured 400 response containing all
     * violation messages.</p>
     *
     * @param ex the constraint violation exception containing one or more
     *           validation failures
     * @return {@code 400 Bad Request} with violation details as a JSON string
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<String> handleConstraintViolation(ConstraintViolationException ex) {
        String violations = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("Constraint violation on cart request: {}", violations);
        return ResponseEntity.badRequest().body(violations);
    }

    /**
     * Handles resource-not-found errors from CartStateService operations.
     *
     * <p>Maps {@link ResourceNotFoundException} to HTTP 404 Not Found with a
     * structured JSON error body, replacing the previous behavior where
     * unhandled {@code RuntimeException}s resulted in HTTP 500.</p>
     *
     * @param ex the resource-not-found exception
     * @return {@code 404 Not Found} with error details as a JSON map
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }

    // -----------------------------------------------------------------------
    // REST Endpoints
    // -----------------------------------------------------------------------

    /**
     * Retrieves the current cart state for the given session.
     *
     * <p>Maps to monolith {@code CartActionBean.viewCart()} (lines 137-139) combined
     * with {@code Cart.getCartItemList()}, {@code Cart.getSubTotal()}, and
     * {@code Cart.getNumberOfItems()}.</p>
     *
     * <p>If the cart does not exist in Redis, an empty cart DTO is returned
     * (matching the monolith behavior where a new {@code Cart()} is created
     * for each new session).</p>
     *
     * @param sessionId the cart identifier — session cookie for anonymous users
     *                  or username for authenticated users
     * @return {@code 200 OK} with the cart state as a {@link CartDTO}
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<CartDTO> getCart(
            @PathVariable @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,128}$",
                    message = "sessionId must be 1-128 alphanumeric or hyphen characters") String sessionId) {
        log.info("Getting cart for session: {}", sessionId);
        CartDTO cart = cartStateService.getCart(sessionId);
        return ResponseEntity.ok(cart);
    }

    /**
     * Adds an item to the cart, or increments its quantity if already present.
     *
     * <p>Maps to monolith {@code CartActionBean.addItemToCart()} (lines 68-87):</p>
     * <ul>
     *   <li>If item already in cart: increment quantity by 1 (monolith line 76)</li>
     *   <li>If item is new: {@link CartStateService} calls the Catalog Service REST
     *       API to fetch item details and stock status, then adds to cart with
     *       quantity=1 (monolith lines 81-83)</li>
     * </ul>
     *
     * <p>The {@code @Valid} annotation triggers {@code @NotBlank} validation on
     * the {@code itemId} field, replicating the monolith's null/empty check
     * (CartActionBean lines 70-73).</p>
     *
     * @param sessionId the cart identifier — session cookie or username
     * @param request   the request body containing the item ID to add
     * @return {@code 200 OK} with the updated cart state as a {@link CartDTO}
     */
    @PostMapping("/{sessionId}/items")
    public ResponseEntity<CartDTO> addItemToCart(
            @PathVariable @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,128}$",
                    message = "sessionId must be 1-128 alphanumeric or hyphen characters") String sessionId,
            @Valid @RequestBody AddItemRequest request) {
        log.info("Adding item {} to cart {}", request.getItemId(), sessionId);
        CartDTO cart = cartStateService.addItem(sessionId, request.getItemId());
        return ResponseEntity.ok(cart);
    }

    /**
     * Removes a specific item from the cart.
     *
     * <p>Maps to monolith {@code CartActionBean.removeItemFromCart()} (lines 94-109).
     * The monolith validates that {@code workingItemId} is not null/empty (lines 96-99)
     * and returns an error if the item is not found (lines 103-105). In the microservice,
     * the item ID comes from the path variable, and the service layer throws an exception
     * if the item is not in the cart.</p>
     *
     * @param sessionId the cart identifier — session cookie or username
     * @param itemId    the catalog item identifier to remove
     * @return {@code 200 OK} with the updated cart state as a {@link CartDTO}
     */
    @DeleteMapping("/{sessionId}/items/{itemId}")
    public ResponseEntity<CartDTO> removeItemFromCart(
            @PathVariable @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,128}$",
                    message = "sessionId must be 1-128 alphanumeric or hyphen characters") String sessionId,
            @PathVariable String itemId) {
        log.info("Removing item {} from cart {}", itemId, sessionId);
        CartDTO cart = cartStateService.removeItemById(sessionId, itemId);
        return ResponseEntity.ok(cart);
    }

    /**
     * Bulk-updates quantities for items in the cart.
     *
     * <p>Maps to monolith {@code CartActionBean.updateCartQuantities()} (lines 116-135).
     * The monolith iterates all cart items and reads individual request parameters per
     * item ID. The microservice accepts a JSON body with a map of itemId to quantity.</p>
     *
     * <p>Quantity update semantics (matching monolith lines 122-128):</p>
     * <ul>
     *   <li>If new quantity &ge; 1: the item's quantity is updated</li>
     *   <li>If new quantity &lt; 1: the item is removed from the cart</li>
     * </ul>
     *
     * @param sessionId       the cart identifier — session cookie or username
     * @param quantityUpdates a map of item IDs to new quantities; entries with
     *                        quantity &lt; 1 trigger item removal
     * @return {@code 200 OK} with the updated cart state as a {@link CartDTO}
     */
    @PutMapping("/{sessionId}")
    public ResponseEntity<CartDTO> updateCartQuantities(
            @PathVariable @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,128}$",
                    message = "sessionId must be 1-128 alphanumeric or hyphen characters") String sessionId,
            @RequestBody Map<String, Integer> quantityUpdates) {
        log.info("Updating cart quantities for session: {}, items: {}",
                sessionId, quantityUpdates.size());
        CartDTO cart = cartStateService.updateQuantities(sessionId, quantityUpdates);
        return ResponseEntity.ok(cart);
    }

    /**
     * Clears the entire cart by removing it from Redis.
     *
     * <p>Maps to monolith {@code CartActionBean.clear()} (lines 145-148):
     * {@code cart = new Cart(); workingItemId = null;}. In the microservice,
     * the cart entry is deleted from Redis entirely.</p>
     *
     * <p>This endpoint is also called internally by the Order Service after
     * successful order placement to clear the user's cart.</p>
     *
     * @param sessionId the cart identifier — session cookie or username
     * @return {@code 204 No Content} with an empty response body
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> clearCart(
            @PathVariable @Pattern(regexp = "^[a-zA-Z0-9\\-]{1,128}$",
                    message = "sessionId must be 1-128 alphanumeric or hyphen characters") String sessionId) {
        log.info("Clearing cart for session: {}", sessionId);
        cartStateService.clearCart(sessionId);
        return ResponseEntity.noContent().build();
    }

    // -----------------------------------------------------------------------
    // Request DTOs
    // -----------------------------------------------------------------------

    /**
     * Request body for the add-item-to-cart endpoint ({@code POST /api/cart/{sessionId}/items}).
     *
     * <p>Mirrors the monolith's {@code CartActionBean.workingItemId} field (line 49):
     * {@code private String workingItemId;} — the single item identifier for
     * add/remove operations.</p>
     *
     * <p>The {@code @NotBlank} annotation on {@code itemId} replicates the monolith's
     * validation (CartActionBean lines 70-73):</p>
     * <pre>
     * if (workingItemId == null || workingItemId.trim().isEmpty()) {
     *     setMessage("Invalid item ID: cannot add item to cart.");
     *     return new ForwardResolution(ERROR);
     * }
     * </pre>
     */
    public static class AddItemRequest {

        /**
         * The catalog item identifier to add to the cart.
         *
         * <p>Must not be blank (null, empty, or whitespace-only). Maps to
         * {@code CartActionBean.workingItemId}. Example values: "EST-1",
         * "EST-14", "EST-28".</p>
         */
        @NotBlank(message = "Item ID is required")
        private String itemId;

        /**
         * Default no-argument constructor required for JSON deserialization
         * by Jackson.
         */
        public AddItemRequest() {
        }

        /**
         * Returns the catalog item identifier.
         *
         * @return the item ID to add to the cart
         */
        public String getItemId() {
            return itemId;
        }

        /**
         * Sets the catalog item identifier.
         *
         * @param itemId the item ID to add to the cart
         */
        public void setItemId(String itemId) {
            this.itemId = itemId;
        }
    }
}
