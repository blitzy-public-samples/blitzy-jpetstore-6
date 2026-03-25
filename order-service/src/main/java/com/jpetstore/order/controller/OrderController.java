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

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jpetstore.order.dto.OrderDTO;
import com.jpetstore.order.dto.OrderRequest;
import com.jpetstore.order.service.OrderService;

import jakarta.validation.Valid;

/**
 * REST controller for order management in the Order Service.
 *
 * <p>Exposes the three core order endpoints defined in the AAP (§0.4.1, §0.5.1):</p>
 * <ul>
 *   <li>{@code POST /api/orders} — Create a new order via Saga orchestration</li>
 *   <li>{@code GET /api/orders?username={username}} — List all orders for a user</li>
 *   <li>{@code GET /api/orders/{id}} — Get a single order by its ID</li>
 * </ul>
 *
 * <p>These endpoints correspond to the monolith's {@code OrderActionBean} methods:</p>
 * <table>
 *   <tr><th>Monolith</th><th>REST Endpoint</th></tr>
 *   <tr><td>{@code OrderActionBean.newOrder()}</td><td>{@code POST /api/orders}</td></tr>
 *   <tr><td>{@code OrderActionBean.listOrders()}</td><td>{@code GET /api/orders?username=}</td></tr>
 *   <tr><td>{@code OrderActionBean.viewOrder()}</td><td>{@code GET /api/orders/{id}}</td></tr>
 * </table>
 *
 * <h3>Order Creation Flow (POST /api/orders)</h3>
 * <p>Per AAP §0.7.1, the order creation endpoint triggers the 3-step Saga:</p>
 * <ol>
 *   <li>CREATE_ORDER — Persist order with PENDING status</li>
 *   <li>RESERVE_INVENTORY — Call Catalog Service to decrement inventory</li>
 *   <li>CONFIRM_ORDER — Update status to CONFIRMED (or FAILED + compensation)</li>
 * </ol>
 *
 * <h3>Error Handling</h3>
 * <p>Provides structured JSON error responses for all failure modes:</p>
 * <ul>
 *   <li>400 Bad Request — Invalid or missing fields in the order request</li>
 *   <li>404 Not Found — Order not found by ID</li>
 *   <li>409 Conflict — Order creation failed (Saga resulted in FAILED status)</li>
 *   <li>500 Internal Server Error — Unexpected system errors</li>
 * </ul>
 *
 * @author Blitzy Platform
 * @see OrderService
 * @see OrderDTO
 * @see OrderRequest
 */
@RestController
@RequestMapping("/api/orders")
@Validated
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    /**
     * Constructs the order controller with its service dependency.
     *
     * @param orderService the order business logic service
     */
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // -----------------------------------------------------------------------
    // REST Endpoints
    // -----------------------------------------------------------------------

    /**
     * Creates a new order via the Saga orchestration pattern.
     *
     * <p>Per AAP §0.4.1: {@code POST /api/orders} — order placement with Saga.
     * The request body contains all shipping, billing, payment, and cart
     * reference data. Line items are populated from the externalized cart
     * state identified by {@code cartSessionId}.</p>
     *
     * <p>The Saga flow (AAP §0.7.1):</p>
     * <ol>
     *   <li>CREATE_ORDER: Persist order with PENDING status, line items, order status</li>
     *   <li>RESERVE_INVENTORY: REST call to Catalog Service to decrement inventory</li>
     *   <li>CONFIRM_ORDER: Update order status to CONFIRMED (or FAILED on error)</li>
     * </ol>
     *
     * <p>Response codes:</p>
     * <ul>
     *   <li>201 Created — order successfully placed and confirmed</li>
     *   <li>409 Conflict — order created but Saga failed (e.g., insufficient inventory)</li>
     *   <li>400 Bad Request — invalid request (missing fields, empty cart)</li>
     *   <li>500 Internal Server Error — unexpected failure</li>
     * </ul>
     *
     * @param request the validated order request DTO
     * @return the created order as {@link OrderDTO}
     */
    @PostMapping
    public ResponseEntity<OrderDTO> createOrder(@Valid @RequestBody OrderRequest request) {
        log.info("Received order creation request for user: {}, cartSession: {}",
                request.getUsername(), request.getCartSessionId());

        OrderDTO createdOrder = orderService.createOrder(request);

        // Check if the Saga completed successfully or resulted in a FAILED state
        if ("FAILED".equals(createdOrder.getStatus())) {
            log.warn("Order {} created but Saga failed — status: FAILED",
                    createdOrder.getOrderId());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(createdOrder);
        }

        log.info("Order {} created successfully with status: {}",
                createdOrder.getOrderId(), createdOrder.getStatus());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdOrder);
    }

    /**
     * Lists all orders for a specific user, sorted by order date descending.
     *
     * <p>Per AAP §0.4.1: {@code GET /api/orders?username={username}} — list orders.
     * Returns an empty list if no orders exist for the given username.</p>
     *
     * <p>Called by the monolith's {@code OrderActionBean.listOrders()} which
     * passes the authenticated user's username.</p>
     *
     * @param username the username to search for (required query parameter)
     * @return list of orders as {@link OrderDTO} objects
     */
    @GetMapping
    public ResponseEntity<List<OrderDTO>> getOrdersByUsername(
            @RequestParam("username") String username) {
        log.debug("Listing orders for user: {}", username);

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        List<OrderDTO> orders = orderService.getOrdersByUsername(username);
        log.debug("Found {} orders for user: {}", orders.size(), username);
        return ResponseEntity.ok(orders);
    }

    /**
     * Retrieves a single order by its primary key.
     *
     * <p>Per AAP §0.4.1: {@code GET /api/orders/{id}} — get order by ID.
     * Returns 404 if the order does not exist.</p>
     *
     * <p>Called by the monolith's {@code OrderActionBean.viewOrder()} which
     * passes the selected order ID from the order list or confirmation page.</p>
     *
     * @param orderId the order identifier
     * @return the order as {@link OrderDTO}, or 404 if not found
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDTO> getOrderById(@PathVariable("orderId") int orderId) {
        log.debug("Retrieving order by id: {}", orderId);

        OrderDTO order = orderService.getOrderById(orderId);
        if (order == null) {
            log.debug("Order not found: {}", orderId);
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(order);
    }

    // -----------------------------------------------------------------------
    // Exception Handlers
    // -----------------------------------------------------------------------

    /**
     * Handles validation errors for order request body.
     *
     * <p>Catches {@link jakarta.validation.ConstraintViolationException} and
     * {@link org.springframework.web.bind.MethodArgumentNotValidException}
     * to return a structured 400 Bad Request response.</p>
     *
     * @param ex the validation exception
     * @return 400 response with error details
     */
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(
            org.springframework.web.bind.MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        log.warn("Order request validation failed: {}", message);
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Validation failed", "details", message));
    }

    /**
     * Handles illegal argument errors (e.g., empty cart, cart not found).
     *
     * @param ex the IllegalArgumentException
     * @return 400 response with error message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(
            IllegalArgumentException ex) {
        log.warn("Order creation failed with IllegalArgumentException: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Handles unexpected runtime errors during order processing.
     *
     * <p>Provides a structured JSON error response instead of exposing
     * internal exception stack traces to clients.</p>
     *
     * @param ex the RuntimeException
     * @return 500 response with error message
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(
            RuntimeException ex) {
        log.error("Unexpected error during order processing: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Internal server error",
                        "message", ex.getMessage() != null ? ex.getMessage() : "Unknown error"));
    }
}
