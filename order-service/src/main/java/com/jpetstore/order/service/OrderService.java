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
package com.jpetstore.order.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.dto.CartItemDTO;
import com.jpetstore.order.dto.OrderDTO;
import com.jpetstore.order.dto.OrderRequest;
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;
import com.jpetstore.order.repository.OrderRepository;
import com.jpetstore.order.saga.OrderSagaOrchestrator;

/**
 * Business-logic service for the Order bounded context.
 *
 * <p>This class replaces the monolith's {@code org.mybatis.jpetstore.service.OrderService},
 * reimplemented on top of Spring Data JPA and the distributed Saga orchestration
 * pattern for cross-service inventory management.</p>
 *
 * <h3>Key responsibilities</h3>
 * <ul>
 *   <li><strong>Order creation</strong>: Converts an {@link OrderRequest} DTO into an
 *       {@link Order} entity, populates line items from the externalized cart state,
 *       and delegates to {@link OrderSagaOrchestrator#executeOrderSaga(Order)} for
 *       the distributed transaction (CREATE_ORDER → RESERVE_INVENTORY → CONFIRM_ORDER).</li>
 *   <li><strong>Order retrieval</strong>: Loads individual orders or order lists via
 *       {@link OrderRepository}, converting entities to {@link OrderDTO} for API responses.</li>
 * </ul>
 *
 * <h3>Monolith method mapping</h3>
 * <table>
 *   <tr><th>Monolith Method</th><th>This Service Method</th><th>Differences</th></tr>
 *   <tr><td>{@code insertOrder(Order)}</td><td>{@link #createOrder(OrderRequest)}</td>
 *       <td>Accepts DTO instead of domain object; delegates to Saga orchestrator
 *       instead of single @Transactional method</td></tr>
 *   <tr><td>{@code getOrder(int)}</td><td>{@link #getOrderById(int)}</td>
 *       <td>Returns Optional of DTO instead of entity; uses Spring Data JPA</td></tr>
 *   <tr><td>{@code getOrdersByUsername(String)}</td><td>{@link #getOrdersByUsername(String)}</td>
 *       <td>Returns List of DTOs; uses derived query method</td></tr>
 * </table>
 *
 * @author Blitzy Platform
 * @see OrderSagaOrchestrator
 * @see OrderRepository
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderSagaOrchestrator sagaOrchestrator;
    private final CartStateService cartStateService;

    /**
     * Constructs the order service with all required dependencies.
     *
     * @param orderRepository   Spring Data JPA repository for Order entities
     * @param sagaOrchestrator  Saga orchestrator for distributed order transactions
     * @param cartStateService  service for externalized cart state management
     */
    public OrderService(OrderRepository orderRepository,
                        OrderSagaOrchestrator sagaOrchestrator,
                        CartStateService cartStateService) {
        this.orderRepository = orderRepository;
        this.sagaOrchestrator = sagaOrchestrator;
        this.cartStateService = cartStateService;
    }

    /**
     * Creates a new order by converting the request DTO into an Order entity,
     * populating line items from the externalized cart, and executing the
     * Saga orchestration flow.
     *
     * <p>The Saga flow (per AAP §0.7.1):</p>
     * <ol>
     *   <li>CREATE_ORDER: Persist the order with PENDING status</li>
     *   <li>RESERVE_INVENTORY: Call Catalog Service to decrement inventory</li>
     *   <li>CONFIRM_ORDER: Update status to CONFIRMED (or FAILED on error)</li>
     * </ol>
     *
     * @param request the validated order request DTO
     * @return the completed order as an {@link OrderDTO}
     * @throws IllegalArgumentException if the cart is empty or not found
     */
    public OrderDTO createOrder(OrderRequest request) {
        log.info("Creating order for user: {}, cartSessionId: {}",
                request.getUsername(), request.getCartSessionId());

        // Build the Order entity from the request DTO
        Order order = buildOrderFromRequest(request);

        // Populate line items from the externalized cart state
        populateLineItemsFromCart(order, request.getCartSessionId());

        if (order.getLineItems().isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot create order: cart is empty for session " + request.getCartSessionId());
        }

        // Calculate total price from line items
        BigDecimal totalPrice = order.getLineItems().stream()
                .map(li -> li.getUnitPrice().multiply(BigDecimal.valueOf(li.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalPrice(totalPrice);

        // Execute the 3-step Saga: CREATE_ORDER → RESERVE_INVENTORY → CONFIRM_ORDER
        Order resultOrder = sagaOrchestrator.executeOrderSaga(order);

        log.info("Order saga completed for user: {}, orderId: {}, status: {}",
                resultOrder.getUsername(), resultOrder.getOrderId(), resultOrder.getStatus());

        return convertToDTO(resultOrder);
    }

    /**
     * Retrieves a single order by its primary key.
     *
     * <p>Replaces the monolith's {@code OrderService.getOrder(int orderId)} method,
     * which JOINed orders with orderstatus. In the microservice architecture,
     * the Order entity has its own status column for the Saga state.</p>
     *
     * @param orderId the order identifier
     * @return the order as an {@link OrderDTO}, or {@code null} if not found
     */
    public OrderDTO getOrderById(int orderId) {
        log.debug("Retrieving order by id: {}", orderId);
        return orderRepository.findById(orderId)
                .map(this::convertToDTO)
                .orElse(null);
    }

    /**
     * Retrieves all orders for a specific user, sorted by order date descending.
     *
     * <p>Replaces the monolith's {@code OrderService.getOrdersByUsername(String)}
     * method. Returns an empty list if no orders exist for the username.</p>
     *
     * @param username the username to search for
     * @return list of orders as {@link OrderDTO} objects
     */
    public List<OrderDTO> getOrdersByUsername(String username) {
        log.debug("Retrieving orders for user: {}", username);
        List<Order> orders = orderRepository.findByUsernameOrderByOrderDateDesc(username);
        return orders.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    // -----------------------------------------------------------------------
    // Private Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Builds an Order entity from the request DTO, mapping all address,
     * payment, and metadata fields.
     *
     * <p>This replaces the monolith's {@code Order.initOrder(Account, Cart)} method,
     * which copied account data into order fields. In the microservice, the client
     * sends all necessary data in the request DTO.</p>
     */
    private Order buildOrderFromRequest(OrderRequest request) {
        Order order = new Order();
        order.setUsername(request.getUsername());
        order.setOrderDate(LocalDateTime.now());

        // Shipping address
        order.setShipAddress1(request.getShipAddress1());
        order.setShipAddress2(request.getShipAddress2());
        order.setShipCity(request.getShipCity());
        order.setShipState(request.getShipState());
        order.setShipZip(request.getShipZip());
        order.setShipCountry(request.getShipCountry());

        // Billing address
        order.setBillAddress1(request.getBillAddress1());
        order.setBillAddress2(request.getBillAddress2());
        order.setBillCity(request.getBillCity());
        order.setBillState(request.getBillState());
        order.setBillZip(request.getBillZip());
        order.setBillCountry(request.getBillCountry());

        // Payment
        order.setCreditCard(request.getCreditCard());
        order.setExpiryDate(request.getExpiryDate());
        order.setCardType(request.getCardType());

        // Names
        order.setBillToFirstName(request.getBillToFirstName());
        order.setBillToLastName(request.getBillToLastName());
        order.setShipToFirstName(request.getShipToFirstName());
        order.setShipToLastName(request.getShipToLastName());

        // Miscellaneous
        order.setCourier(request.getCourier());
        order.setLocale(request.getLocale());

        return order;
    }

    /**
     * Populates the order's line items from the externalized cart state.
     *
     * <p>Retrieves the cart from Redis-backed CartStateService and converts each
     * cart item into a LineItem entity. Line numbers are assigned sequentially
     * starting from 1, matching the monolith's convention.</p>
     *
     * @param order             the Order entity to populate
     * @param cartSessionId     the cart identifier (session ID or username)
     * @throws IllegalArgumentException if the cart is not found
     */
    private void populateLineItemsFromCart(Order order, String cartSessionId) {
        CartDTO cart;
        try {
            cart = cartStateService.getCart(cartSessionId);
        } catch (Exception e) {
            log.warn("Failed to retrieve cart for session {}: {}", cartSessionId, e.getMessage());
            throw new IllegalArgumentException(
                    "Cart not found for session: " + cartSessionId);
        }

        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            log.warn("Cart is empty for session: {}", cartSessionId);
            return;
        }

        List<LineItem> lineItems = new ArrayList<>();
        int lineNum = 1;
        for (CartItemDTO cartItem : cart.getItems()) {
            LineItem lineItem = new LineItem();
            lineItem.setLineNum(lineNum);
            lineItem.setItemId(cartItem.getItemId());
            lineItem.setQuantity(cartItem.getQuantity());
            lineItem.setUnitPrice(cartItem.getUnitPrice() != null
                    ? cartItem.getUnitPrice() : BigDecimal.ZERO);
            lineItems.add(lineItem);
            lineNum++;
        }

        order.setLineItems(lineItems);
    }

    /**
     * Converts an Order entity to an OrderDTO for REST API responses.
     *
     * <p>Maps all entity fields to the DTO, including line item details.
     * Handles lazy-loaded lineItems collection by safely accessing it
     * within the transactional context.</p>
     */
    private OrderDTO convertToDTO(Order order) {
        OrderDTO dto = new OrderDTO();
        dto.setOrderId(order.getOrderId());
        dto.setUsername(order.getUsername());
        dto.setOrderDate(order.getOrderDate());

        // Shipping address
        dto.setShipAddress1(order.getShipAddress1());
        dto.setShipAddress2(order.getShipAddress2());
        dto.setShipCity(order.getShipCity());
        dto.setShipState(order.getShipState());
        dto.setShipZip(order.getShipZip());
        dto.setShipCountry(order.getShipCountry());

        // Billing address
        dto.setBillAddress1(order.getBillAddress1());
        dto.setBillAddress2(order.getBillAddress2());
        dto.setBillCity(order.getBillCity());
        dto.setBillState(order.getBillState());
        dto.setBillZip(order.getBillZip());
        dto.setBillCountry(order.getBillCountry());

        // Payment
        dto.setCreditCard(order.getCreditCard());
        dto.setExpiryDate(order.getExpiryDate());
        dto.setCardType(order.getCardType());

        // Names
        dto.setBillToFirstName(order.getBillToFirstName());
        dto.setBillToLastName(order.getBillToLastName());
        dto.setShipToFirstName(order.getShipToFirstName());
        dto.setShipToLastName(order.getShipToLastName());

        // Miscellaneous
        dto.setCourier(order.getCourier());
        dto.setTotalPrice(order.getTotalPrice());
        dto.setLocale(order.getLocale());
        dto.setStatus(order.getStatus());

        // Line items
        try {
            List<LineItem> lineItems = order.getLineItems();
            if (lineItems != null) {
                List<OrderDTO.LineItemDetail> lineItemDetails = lineItems.stream()
                        .map(li -> {
                            OrderDTO.LineItemDetail detail = new OrderDTO.LineItemDetail();
                            detail.setLineNumber(li.getLineNum());
                            detail.setItemId(li.getItemId());
                            detail.setQuantity(li.getQuantity());
                            detail.setUnitPrice(li.getUnitPrice());
                            detail.setTotal(li.getTotal());
                            return detail;
                        })
                        .collect(Collectors.toList());
                dto.setLineItems(lineItemDetails);
            }
        } catch (Exception e) {
            // Lazy-loading may fail outside transactional context for list queries;
            // line items are omitted in that case (they can be fetched via getOrderById)
            log.debug("Could not load line items for order {}: {}", order.getOrderId(), e.getMessage());
        }

        return dto;
    }
}
