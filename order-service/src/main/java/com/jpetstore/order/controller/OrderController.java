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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * <p>Errors are handled by letting exceptions propagate to Spring's default
 * exception handling mechanism or a {@code @ControllerAdvice}:</p>
 * <ul>
 *   <li>400 Bad Request — Validation failures from {@code @Valid} on OrderRequest
 *       (auto-handled by Spring)</li>
 *   <li>500 Internal Server Error — Runtime exceptions from OrderService</li>
 * </ul>
 *
 * <h3>Authentication Note</h3>
 * <p>Per AAP §0.7.6, the API Gateway's JWT filter ensures only authenticated
 * users reach protected endpoints. The controller trusts the {@code username}
 * parameter — the gateway validates the JWT and passes claims downstream.</p>
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
     * Constructs the OrderController with its single service dependency.
     *
     * <p>Uses Spring's constructor injection (no {@code @Autowired} needed for
     * single-constructor classes in Spring Boot). The OrderService bean is
     * automatically resolved from the application context.</p>
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
     * {@code orderService.getOrdersByUsername(username)}. In the microservice,
     * the authenticated username is passed as a query parameter — the API
     * Gateway's JWT filter ensures only the authenticated user's own username
     * is passed, preserving the monolith's access control behavior.</p>
     *
     * @param username the username to search for (required query parameter)
     * @return 200 OK with list of {@link OrderDTO} objects (may be empty)
     */
    @GetMapping
    public ResponseEntity<List<OrderDTO>> getOrdersByUsername(@RequestParam String username) {
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
     * a RuntimeException which results in an appropriate error response
     * (handled by Spring's default exception handling or a
     * {@code @ControllerAdvice}).</p>
     *
     * @param orderId the order identifier (integer, from path variable)
     * @return 200 OK with the {@link OrderDTO}
     */
    @GetMapping("/{id}")
    public ResponseEntity<OrderDTO> getOrder(@PathVariable("id") int orderId) {
        log.info("Retrieving order: {}", orderId);
        OrderDTO order = orderService.getOrder(orderId);
        return ResponseEntity.ok(order);
    }
}
