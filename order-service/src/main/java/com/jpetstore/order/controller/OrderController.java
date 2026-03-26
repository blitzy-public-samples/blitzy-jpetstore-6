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

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jpetstore.order.dto.OrderDTO;
import com.jpetstore.order.dto.OrderRequest;
import com.jpetstore.order.exception.ResourceNotFoundException;
import com.jpetstore.order.service.OrderService;

import jakarta.validation.Valid;

/**
 * REST controller for order management in the Order Service microservice.
 *
 * <p>This controller is a <strong>thin HTTP adapter</strong> that maps REST endpoints
 * to {@link OrderService} method calls. It contains zero business logic — all order
 * construction, total price computation, line item creation, saga orchestration,
 * user verification, and cart retrieval is delegated to {@link OrderService}.</p>
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
 *   <tr><th>Monolith Method</th><th>REST Endpoint</th></tr>
 *   <tr><td>{@code OrderActionBean.newOrder()} (lines 142-164)</td>
 *       <td>{@code POST /api/orders}</td></tr>
 *   <tr><td>{@code OrderActionBean.listOrders()} (lines 107-112)</td>
 *       <td>{@code GET /api/orders?username=}</td></tr>
 *   <tr><td>{@code OrderActionBean.viewOrder()} (lines 171-185)</td>
 *       <td>{@code GET /api/orders/{id}}</td></tr>
 * </table>
 *
 * <h3>Order Creation Flow (POST /api/orders)</h3>
 * <p>Per AAP §0.7.1, the order creation endpoint triggers the 3-step Saga
 * orchestrated by {@link OrderService#insertOrder(OrderRequest)}:</p>
 * <ol>
 *   <li>CREATE_ORDER — Persist order with PENDING status</li>
 *   <li>RESERVE_INVENTORY — Call Catalog Service to decrement inventory</li>
 *   <li>CONFIRM_ORDER — Update status to CONFIRMED (or FAILED + compensation)</li>
 * </ol>
 *
 * <h3>Error Handling</h3>
 * <p>Errors are handled via controller-level {@code @ExceptionHandler} methods:</p>
 * <ul>
 *   <li>400 Bad Request — Validation failures from {@code @Valid} on OrderRequest
 *       (auto-handled by Spring)</li>
 *   <li>403 Forbidden — Authorization failure when the requested username does not
 *       match the authenticated principal (BOLA prevention per OWASP API #1)</li>
 *   <li>404 Not Found — Order not found by ID (via {@link ResourceNotFoundException})</li>
 *   <li>500 Internal Server Error — Unexpected runtime exceptions from OrderService</li>
 * </ul>
 *
 * <h3>Authorization</h3>
 * <p>Per AAP §0.7.6, the API Gateway validates the JWT and passes it downstream.
 * This controller extracts the authenticated username from the JWT token's
 * {@code sub} claim and compares it against the requested username to enforce
 * object-level authorization — preventing users from accessing other users' data.
 * This mirrors the monolith's implicit protection where {@code OrderActionBean.listOrders()}
 * uses the session-scoped account bean (not a user-supplied parameter).</p>
 *
 * @author Blitzy Platform
 * @see OrderService
 * @see OrderDTO
 * @see OrderRequest
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    /**
     * Order business logic service. All operations — order creation with Saga
     * orchestration, order retrieval, and order listing — are delegated to this
     * service. The controller performs no business logic.
     */
    private final OrderService orderService;

    /**
     * Jackson ObjectMapper for JWT payload deserialization. Reused across requests
     * for efficient JSON parsing when extracting the {@code sub} claim from
     * JWT tokens during authorization checks.
     */
    private final ObjectMapper objectMapper;

    /**
     * Constructs the OrderController with its dependencies.
     *
     * <p>Uses Spring's constructor injection (no {@code @Autowired} needed for
     * single-constructor classes in Spring Boot). The OrderService bean and
     * ObjectMapper are automatically resolved from the application context.</p>
     *
     * @param orderService the order business logic service
     * @param objectMapper Jackson ObjectMapper for JWT payload parsing
     */
    public OrderController(OrderService orderService, ObjectMapper objectMapper) {
        this.orderService = orderService;
        this.objectMapper = objectMapper;
    }

    // -----------------------------------------------------------------------
    // REST Endpoints
    // -----------------------------------------------------------------------

    /**
     * Creates a new order via the Saga orchestration pattern.
     *
     * <p>Maps to the monolith's {@code OrderActionBean.newOrder()} (lines 142-164)
     * combined with {@code OrderService.insertOrder(Order)} (lines 59-77).
     * The monolith's multi-step checkout state machine (newOrderForm → shipping
     * → confirm → submit) remains in the monolith's updated ActionBeans. This
     * REST API only receives the FINAL confirmed order request.</p>
     *
     * <p>The {@code @Valid} annotation triggers Jakarta Bean Validation on all
     * {@code @NotBlank} constraints defined on {@link OrderRequest} fields
     * (username, addresses, payment info, cartSessionId). Spring Boot
     * auto-returns 400 Bad Request on validation failure.</p>
     *
     * <p>All business logic is delegated to {@link OrderService#insertOrder(OrderRequest)}
     * which performs: user verification via AccountServiceClient, cart retrieval
     * from Redis via CartStateService, Order entity construction, Saga execution
     * via OrderSagaOrchestrator, cart clearing, and DTO conversion.</p>
     *
     * @param request the validated order request DTO containing username,
     *                shipping/billing addresses, payment info, and cart session ID
     * @return 201 Created with the created {@link OrderDTO}
     */
    @PostMapping
    public ResponseEntity<OrderDTO> createOrder(@Valid @RequestBody OrderRequest request) {
        log.info("Creating order for user: {}", request.getUsername());
        OrderDTO order = orderService.insertOrder(request);
        log.info("Order created successfully: orderId={}", order.getOrderId());
        return ResponseEntity.status(HttpStatus.CREATED).body(order);
    }

    /**
     * Lists all orders for a specific user.
     *
     * <p>Maps to the monolith's {@code OrderActionBean.listOrders()} (lines 107-112)
     * which retrieves the authenticated user from session and calls
     * {@code orderService.getOrdersByUsername(username)}. In the monolith, the
     * session-scoped {@code AccountActionBean} inherently restricts access to
     * the authenticated user's own data. In the microservice, this method
     * enforces the same restriction by extracting the authenticated username
     * from the JWT token's {@code sub} claim and comparing it against the
     * requested username.</p>
     *
     * <h4>Authorization (BOLA Prevention — OWASP API Security #1)</h4>
     * <p>The API Gateway validates the JWT signature and forwards the token
     * downstream. This method decodes the JWT payload (without re-validating
     * the signature, since the gateway already did) and extracts the {@code sub}
     * claim to determine the authenticated user's identity. If the authenticated
     * username does not match the requested username, a 403 Forbidden response
     * is returned. If no JWT token is present (e.g., during testing or internal
     * service calls), the request proceeds without authorization checks.</p>
     *
     * @param username            the username to search for (required query parameter)
     * @param authorizationHeader the Authorization header containing the Bearer JWT token
     *                            (optional — may be absent for internal service-to-service calls)
     * @return 200 OK with list of {@link OrderDTO} objects (may be empty),
     *         or 403 Forbidden if the authenticated user does not match the requested username
     */
    @GetMapping
    public ResponseEntity<List<OrderDTO>> getOrdersByUsername(
            @RequestParam String username,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        // Authorization check: verify the authenticated user matches the requested username.
        // This prevents Broken Object Level Authorization (BOLA — OWASP API Security #1)
        // where any authenticated user could read another user's order history.
        // The monolith's OrderActionBean.listOrders() inherently prevents this because it
        // uses the session-scoped account bean, not a user-supplied parameter.
        String authenticatedUser = extractUsernameFromToken(authorizationHeader);
        if (authenticatedUser != null && !authenticatedUser.equals(username)) {
            log.warn("Authorization denied: authenticated user '{}' attempted to access "
                    + "orders for user '{}'", authenticatedUser, username);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        log.info("Listing orders for user: {}", username);
        List<OrderDTO> orders = orderService.getOrdersByUsername(username);
        return ResponseEntity.ok(orders);
    }

    /**
     * Retrieves a single order by its primary key.
     *
     * <p>Maps to the monolith's {@code OrderActionBean.viewOrder()} (lines 171-185)
     * combined with {@code OrderService.getOrder(int)} (lines 87-99). In the
     * monolith, {@code viewOrder()} checks ownership by comparing the session
     * user's username with the order's username. In the microservice, ownership
     * verification can be handled in the service layer or by the API Gateway's
     * JWT filter which ensures only authenticated users reach this endpoint.</p>
     *
     * <p>If the order is not found, {@link OrderService#getOrder(int)} throws
     * a {@link ResourceNotFoundException} which is caught by the controller-level
     * {@link #handleResourceNotFound(ResourceNotFoundException)} exception handler
     * and returns a 404 Not Found JSON response.</p>
     *
     * @param orderId the order identifier (integer, from path variable)
     * @return 200 OK with the {@link OrderDTO}, or 404 Not Found if the order does not exist
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderDTO> getOrder(@PathVariable("id") int orderId) {
        log.info("Retrieving order: {}", orderId);
        OrderDTO order = orderService.getOrder(orderId);
        return ResponseEntity.ok(order);
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Exception Handlers
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Handles {@link ResourceNotFoundException} thrown by service methods when
     * a requested entity (order, line item, etc.) does not exist.
     *
     * <p>Returns a 404 Not Found response with a JSON body containing the error
     * message. This matches the error handling pattern used in {@code CartController}
     * for consistency across the Order Service's REST API.</p>
     *
     * @param ex the exception containing the error message
     * @return 404 Not Found response with JSON body {@code {"error": "<message>"}}
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Handles {@link IllegalStateException} thrown by the order creation flow
     * when a business-rule precondition fails — e.g., the referenced account
     * does not exist in the Account Service, the cart is empty, or a Saga step
     * encounters an unrecoverable validation error.
     *
     * <p>Returns a 422 Unprocessable Entity response with a descriptive error
     * message. This prevents the default Spring Boot behavior of returning a
     * generic 500 Internal Server Error for uncaught exceptions, enabling API
     * consumers to distinguish between input/validation errors (4xx) and true
     * server errors (5xx).</p>
     *
     * <p><strong>Examples of exceptions caught here:</strong></p>
     * <ul>
     *   <li>"Account not found for username: someUser" — from
     *       {@link OrderService#createOrder} when AccountServiceClient
     *       cannot verify the user</li>
     *   <li>"Cart is empty" — when attempting to create an order with no items</li>
     * </ul>
     *
     * @param ex the exception containing the business-rule violation message
     * @return 422 Unprocessable Entity response with JSON body
     *         {@code {"error": "<message>"}}
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        log.warn("Order creation rejected — business rule violation: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", ex.getMessage()));
    }

    /**
     * Handles {@link IllegalArgumentException} thrown when order request
     * parameters fail programmatic validation beyond Jakarta Bean Validation —
     * e.g., invalid field values that pass {@code @NotBlank} but are logically
     * invalid.
     *
     * <p>Returns a 400 Bad Request response with the exception message.</p>
     *
     * @param ex the exception containing the validation failure message
     * @return 400 Bad Request response with JSON body
     *         {@code {"error": "<message>"}}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Order request rejected — invalid argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Private Helper Methods
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the authenticated username from a JWT Bearer token in the
     * Authorization header.
     *
     * <p>The JWT signature is NOT re-validated here because the API Gateway
     * has already verified it (per AAP §0.7.6). This method only decodes the
     * payload (middle segment) using Base64 to read the {@code sub} claim,
     * which contains the authenticated username.</p>
     *
     * <p>Returns {@code null} if:</p>
     * <ul>
     *   <li>The Authorization header is null or empty</li>
     *   <li>The header does not start with "Bearer "</li>
     *   <li>The JWT token is malformed (not 3 dot-separated parts)</li>
     *   <li>The payload cannot be parsed as JSON</li>
     *   <li>The {@code sub} claim is missing from the payload</li>
     * </ul>
     *
     * <p>When {@code null} is returned, the caller should allow the request
     * to proceed (graceful degradation for service-to-service calls or testing
     * scenarios where no JWT is present).</p>
     *
     * @param authorizationHeader the full Authorization header value (e.g., "Bearer eyJ...")
     * @return the username from the JWT's {@code sub} claim, or {@code null} if extraction fails
     */
    private String extractUsernameFromToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }

        try {
            String token = authorizationHeader.substring(7);
            // JWT structure: header.payload.signature — we only need the payload (index 1)
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                log.warn("Malformed JWT token: expected at least 2 dot-separated parts");
                return null;
            }

            // Decode the payload (Base64URL-encoded JSON)
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode jsonNode = objectMapper.readTree(payload);
            JsonNode subNode = jsonNode.get("sub");
            if (subNode == null || subNode.isNull()) {
                log.warn("JWT token missing 'sub' claim");
                return null;
            }
            return subNode.asText();
        } catch (Exception ex) {
            log.warn("Failed to extract username from JWT token: {}", ex.getMessage());
            return null;
        }
    }
}
