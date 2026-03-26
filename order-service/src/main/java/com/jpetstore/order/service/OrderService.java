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
import org.springframework.transaction.annotation.Transactional;

import com.jpetstore.order.client.AccountServiceClient;
import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.dto.CartItemDTO;
import com.jpetstore.order.dto.OrderDTO;
import com.jpetstore.order.dto.OrderRequest;
import com.jpetstore.order.entity.CartState;
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;
import com.jpetstore.order.entity.OrderStatus;
import com.jpetstore.order.exception.ResourceNotFoundException;
import com.jpetstore.order.repository.LineItemRepository;
import com.jpetstore.order.repository.OrderRepository;
import com.jpetstore.order.repository.OrderStatusRepository;
import com.jpetstore.order.saga.OrderSagaOrchestrator;

/**
 * Primary business logic service for the Order Service microservice.
 *
 * <p>This class replaces the monolith's {@code org.mybatis.jpetstore.service.OrderService}
 * (133 lines) with a Spring Boot 3 service that uses JPA repositories instead of
 * MyBatis mappers, delegates distributed transactions to the Saga pattern via
 * {@link OrderSagaOrchestrator}, and retrieves cart state from Redis via
 * {@link CartStateService}.</p>
 *
 * <h3>Key Transformation Differences from Monolith</h3>
 * <ul>
 *   <li><b>JPA Repositories</b> replace MyBatis Mappers ({@code OrderMapper},
 *       {@code LineItemMapper}, {@code SequenceMapper}, {@code ItemMapper})</li>
 *   <li><b>PostgreSQL sequence</b> ({@code order_id_seq}) replaces the non-thread-safe
 *       {@code getNextId("ordernum")} read-then-update pattern (AAP Section 0.7.3)</li>
 *   <li><b>Saga pattern</b> via {@link OrderSagaOrchestrator} replaces the monolith's
 *       single {@code @Transactional} boundary that spanned Order and Catalog tables</li>
 *   <li><b>{@link OrderRequest} DTO</b> replaces the monolith's pattern of receiving
 *       a pre-populated {@code Order} domain object from the ActionBean</li>
 *   <li><b>Redis cart state</b> via {@link CartStateService} replaces the session-scoped
 *       {@code CartActionBean}</li>
 *   <li><b>Account verification</b> via {@link AccountServiceClient} replaces the
 *       session-based authenticated check in {@code OrderActionBean.newOrderForm()}</li>
 * </ul>
 *
 * <h3>Eliminated Monolith Patterns</h3>
 * <ul>
 *   <li>{@code getNextId(String)} method — eliminated entirely; PostgreSQL sequences
 *       provide thread-safe, atomic ID generation via {@code @GeneratedValue}</li>
 *   <li>{@code SequenceMapper} dependency — the {@code sequence} table is not migrated</li>
 *   <li>{@code ItemMapper} dependency — inventory operations go through
 *       {@link CatalogServiceClient} REST calls, coordinated by the Saga</li>
 *   <li>Direct {@code @Transactional} on {@code insertOrder()} — the distributed
 *       transaction is handled by the Saga orchestrator; only read methods use
 *       {@code @Transactional(readOnly = true)}</li>
 * </ul>
 *
 * <h3>Cross-Service Communication</h3>
 * <p>Per AAP Section 0.8.1: "No service may access another service's database directly."
 * All cross-service data access goes through REST clients:
 * {@link AccountServiceClient} for user verification and
 * {@link CatalogServiceClient} for item detail enrichment.</p>
 *
 * @author Blitzy Platform
 * @see OrderSagaOrchestrator
 * @see CartStateService
 * @see AccountServiceClient
 * @see CatalogServiceClient
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /**
     * Default maximum number of orders returned by {@link #getOrdersByUsername(String)}.
     * Prevents unbounded result sets for users with many orders. Value of 100 is a
     * sensible default for the JPetStore pet store context where order counts per user
     * are typically small.
     */
    static final int DEFAULT_ORDER_LIST_LIMIT = 100;

    /** Spring Data JPA repository for Order entity. Used for read-only queries. */
    private final OrderRepository orderRepository;

    /** Spring Data JPA repository for OrderStatus entity. Used for status record queries. */
    private final OrderStatusRepository orderStatusRepository;

    /** Spring Data JPA repository for LineItem entity. Used for explicit line item loading. */
    private final LineItemRepository lineItemRepository;

    /** Saga coordinator for distributed order transactions. */
    private final OrderSagaOrchestrator orderSagaOrchestrator;

    /** Redis-backed cart state management service. */
    private final CartStateService cartStateService;

    /** REST client for Catalog Service (item details, inventory operations). */
    private final CatalogServiceClient catalogServiceClient;

    /** REST client for Account Service (user verification). */
    private final AccountServiceClient accountServiceClient;

    /**
     * Constructs the OrderService with all 7 required dependencies injected via
     * Spring constructor injection.
     *
     * <p>All dependencies are injected through a single constructor, following
     * Spring best practices for immutable service design. No {@code @Autowired}
     * annotation is needed because Spring automatically detects the single
     * constructor.</p>
     *
     * @param orderRepository       Spring Data JPA repository for Order entity
     * @param orderStatusRepository Spring Data JPA repository for OrderStatus entity
     * @param lineItemRepository    Spring Data JPA repository for LineItem entity
     * @param orderSagaOrchestrator Saga coordinator for distributed order transactions
     * @param cartStateService      Redis-backed cart state management service
     * @param catalogServiceClient  REST client for Catalog Service communication
     * @param accountServiceClient  REST client for Account Service communication
     */
    public OrderService(OrderRepository orderRepository,
                        OrderStatusRepository orderStatusRepository,
                        LineItemRepository lineItemRepository,
                        OrderSagaOrchestrator orderSagaOrchestrator,
                        CartStateService cartStateService,
                        CatalogServiceClient catalogServiceClient,
                        AccountServiceClient accountServiceClient) {
        this.orderRepository = orderRepository;
        this.orderStatusRepository = orderStatusRepository;
        this.lineItemRepository = lineItemRepository;
        this.orderSagaOrchestrator = orderSagaOrchestrator;
        this.cartStateService = cartStateService;
        this.catalogServiceClient = catalogServiceClient;
        this.accountServiceClient = accountServiceClient;
    }

    // -----------------------------------------------------------------------
    // Public Methods (Schema exports: insertOrder, getOrder, getOrdersByUsername)
    // -----------------------------------------------------------------------

    /**
     * Places a new order, orchestrating the distributed transaction via the Saga pattern.
     *
     * <p>Replaces the monolith's {@code OrderService.insertOrder(Order)} (lines 59-77)
     * which executed 2N+4 SQL operations in a single {@code @Transactional} boundary.
     * In the microservice, the distributed transaction is delegated to
     * {@link OrderSagaOrchestrator#executeOrderSaga(Order)}.</p>
     *
     * <h4>Processing Flow</h4>
     * <ol>
     *   <li>Verify user exists via {@link AccountServiceClient#accountExists(String)}</li>
     *   <li>Retrieve cart contents from Redis via {@link CartStateService#getCart(String)}</li>
     *   <li>Validate cart is not empty</li>
     *   <li>Convert cart items to {@link CartState.CartItemData} for internal processing</li>
     *   <li>Construct {@link Order} entity from {@link OrderRequest} fields and cart items</li>
     *   <li>Delegate to {@link OrderSagaOrchestrator#executeOrderSaga(Order)} for:
     *       local order write (PENDING), inventory decrement via Catalog Service,
     *       and status confirmation (CONFIRMED/FAILED)</li>
     *   <li>Clear cart via {@link CartStateService#clearCart(String)} on success</li>
     *   <li>Convert saved Order to {@link OrderDTO} and return</li>
     * </ol>
     *
     * <h4>Order-First Write (AAP Section 0.7.1)</h4>
     * <p>The order record is written first in PENDING state so that there is always
     * a durable record of the attempt. This prevents "phantom decrements" where
     * inventory is reserved but no order record exists.</p>
     *
     * @param request the order creation request containing username, addresses,
     *                payment info, and cart session ID for externalized cart reference
     * @return the created order as an {@link OrderDTO}
     * @throws RuntimeException if user account not found, cart is empty, or
     *                          saga execution fails
     */
    public OrderDTO insertOrder(OrderRequest request) {
        log.info("Processing order for user: {}, cartSessionId: {}",
                request.getUsername(), request.getCartSessionId());

        // Step 1: Verify user exists via Account Service REST call.
        // Replaces the monolith's session-based account lookup in OrderActionBean.newOrderForm()
        if (!accountServiceClient.accountExists(request.getUsername())) {
            log.error("Order rejected: account not found for username: {}", request.getUsername());
            throw new IllegalStateException("Account not found for username: " + request.getUsername());
        }
        log.debug("Account verified for user: {}", request.getUsername());

        // Step 2: Retrieve cart contents from Redis via CartStateService.
        // Replaces the monolith's session-scoped CartActionBean
        CartDTO cart = cartStateService.getCart(request.getCartSessionId());
        if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
            log.error("Order rejected: cart is empty or not found for session: {}",
                    request.getCartSessionId());
            throw new IllegalStateException(
                    "Cart is empty or not found for session: " + request.getCartSessionId());
        }
        List<CartItemDTO> dtoItems = cart.getItems();
        log.debug("Retrieved {} items from cart session: {}",
                dtoItems.size(), request.getCartSessionId());

        // Step 3: Convert CartItemDTO objects to CartState.CartItemData for internal processing.
        // CartState.CartItemData is the canonical model for cart items within the order service,
        // matching the underlying Redis hash entity structure (itemId, quantity, unitPrice).
        List<CartState.CartItemData> cartItems = new ArrayList<>();
        for (CartItemDTO dto : dtoItems) {
            CartState.CartItemData data = new CartState.CartItemData();
            data.setItemId(dto.getItemId());
            data.setQuantity(dto.getQuantity());
            data.setUnitPrice(dto.getUnitPrice());
            cartItems.add(data);
        }

        // Step 4: Build Order entity from request DTO and cart items
        Order order = buildOrderFromRequest(request, cartItems);
        log.debug("Order entity built: username={}, totalPrice={}, lineItems={}",
                order.getUsername(), order.getTotalPrice(), order.getLineItems().size());

        // Step 5: Delegate to Saga orchestrator for distributed transaction.
        // The orchestrator handles: local order write (PENDING) -> inventory decrement
        // via Catalog Service REST -> confirmation (CONFIRMED/FAILED)
        Order savedOrder = orderSagaOrchestrator.executeOrderSaga(order);
        log.info("Order saga completed: orderId={}, status={}",
                savedOrder.getOrderId(), savedOrder.getStatus());

        // Step 6: Clear cart ONLY after successful order placement.
        // The monolith only clears the cart on a successful order — the cart persists
        // if the order fails (e.g., insufficient inventory). The saga may return an
        // Order with status=FAILED without throwing an exception (when inventory
        // reservation fails gracefully), so we must check the status before clearing.
        // This preserves the monolith's behavior per AAP §0.8.1 zero business logic change.
        if ("CONFIRMED".equals(savedOrder.getStatus())) {
            cartStateService.clearCart(request.getCartSessionId());
            log.debug("Cart cleared for session: {}", request.getCartSessionId());
        } else {
            log.warn("Order saga did not confirm order (status={}). Cart NOT cleared for session: {}",
                    savedOrder.getStatus(), request.getCartSessionId());
        }

        // Step 7: Convert and return
        OrderDTO result = convertToDTO(savedOrder);
        log.info("Order successfully placed: orderId={}, username={}, totalPrice={}",
                result.getOrderId(), result.getUsername(), result.getTotalPrice());
        return result;
    }

    /**
     * Retrieves a single order by its ID.
     *
     * <p>Replaces the monolith's {@code OrderService.getOrder(int)} (lines 87-99).
     * In the monolith, line items were loaded separately via
     * {@code lineItemMapper.getLineItemsByOrderId()} and each line item was enriched
     * with item details via {@code itemMapper.getItem()}. In the microservice,
     * line items are explicitly loaded via {@link LineItemRepository} for deterministic
     * behavior, and optional item enrichment is performed via
     * {@link CatalogServiceClient} for operational visibility.</p>
     *
     * @param orderId the order identifier to look up
     * @return the order as an {@link OrderDTO}
     * @throws ResourceNotFoundException if the order is not found (results in HTTP 404
     *         via {@code @ExceptionHandler} in OrderController)
     */
    @Transactional(readOnly = true)
    public OrderDTO getOrder(int orderId) {
        log.debug("Retrieving order: orderId={}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> {
                    log.error("Order not found: orderId={}", orderId);
                    return new ResourceNotFoundException("Order not found with id: " + orderId);
                });

        // Explicitly load line items via repository to avoid lazy-loading issues
        // outside transactional context. Uses LineItemRepository.findByOrderId(int).
        List<LineItem> lineItems = lineItemRepository.findByOrderId(orderId);
        order.setLineItems(lineItems);

        // Load order status records for operational visibility.
        // Uses OrderStatusRepository.findByOrderId(int) and OrderStatus getters.
        List<OrderStatus> statusRecords = orderStatusRepository.findByOrderId(orderId);
        if (!statusRecords.isEmpty()) {
            OrderStatus latestStatus = statusRecords.get(0);
            log.debug("Order {} has {} status records, latest: lineNum={}, status={}, timestamp={}",
                    orderId, statusRecords.size(),
                    latestStatus.getLineNum(), latestStatus.getStatus(),
                    latestStatus.getTimestamp());
        }

        // Optional enrichment: log catalog item details for each line item.
        // Replicates monolith OrderService.getOrder() lines 93-95 where
        // itemMapper.getItem() and itemMapper.getInventoryQuantity() were called.
        // In the microservice, this data comes from Catalog Service REST API.
        // Uses CatalogServiceClient.getItem(String) for cross-service enrichment.
        // PERFORMANCE: Guard with isDebugEnabled() to avoid N cross-service REST calls
        // when debug logging is disabled. Each call adds network latency and is only
        // useful for diagnostic purposes — the order data is complete without enrichment.
        if (log.isDebugEnabled()) {
            for (LineItem lineItem : lineItems) {
                try {
                    catalogServiceClient.getItem(lineItem.getItemId()).ifPresent(itemData ->
                            log.debug("Line item {} enriched: catalog data available for itemId={}",
                                    lineItem.getLineNum(), lineItem.getItemId()));
                } catch (Exception e) {
                    // Graceful degradation per AAP Section 0.7.4: when Catalog Service is
                    // unavailable, the order is still returned with line item data but without
                    // catalog enrichment. A warning is logged for operational visibility.
                    log.warn("Failed to enrich line item {} (itemId={}) with catalog data: {}",
                            lineItem.getLineNum(), lineItem.getItemId(), e.getMessage());
                }
            }
        }

        OrderDTO dto = convertToDTO(order);
        log.debug("Order retrieved: orderId={}, username={}, lineItems={}",
                dto.getOrderId(), dto.getUsername(),
                dto.getLineItems() != null ? dto.getLineItems().size() : 0);
        return dto;
    }

    /**
     * Retrieves orders for a given username, ordered by date descending, with a
     * sensible default limit to prevent excessively large result sets.
     *
     * <p>Replaces the monolith's {@code OrderService.getOrdersByUsername(String)}
     * (lines 109-111) which delegated to {@code orderMapper.getOrdersByUsername()}.
     * The repository method {@code findByUsernameOrderByOrderDateDesc()} preserves
     * the monolith's ORDER BY ORDERDATE descending sort order.</p>
     *
     * <p>A default limit of {@value #DEFAULT_ORDER_LIST_LIMIT} orders is applied to
     * prevent unbounded result sets. A user with many orders would only receive the
     * most recent orders. This is a safety measure — the monolith's typical usage
     * pattern involves small order counts per user (pet store context).</p>
     *
     * @param username the username to query orders for
     * @return a list of {@link OrderDTO}s, ordered by date descending, limited to
     *         {@value #DEFAULT_ORDER_LIST_LIMIT} entries (may be empty)
     */
    @Transactional(readOnly = true)
    public List<OrderDTO> getOrdersByUsername(String username) {
        log.debug("Retrieving orders for user: {}", username);

        List<Order> orders = orderRepository.findByUsernameOrderByOrderDateDesc(username);

        // Apply a sensible default limit to prevent large result sets.
        // Stream.limit() returns at most DEFAULT_ORDER_LIST_LIMIT elements.
        List<OrderDTO> result = orders.stream()
                .limit(DEFAULT_ORDER_LIST_LIMIT)
                .map(this::convertToDTO)
                .collect(Collectors.toList());

        if (orders.size() > DEFAULT_ORDER_LIST_LIMIT) {
            log.info("Order list truncated for user '{}': {} total orders, returning {}",
                    username, orders.size(), DEFAULT_ORDER_LIST_LIMIT);
        }
        log.debug("Found {} orders for user: {}", result.size(), username);
        return result;
    }

    // -----------------------------------------------------------------------
    // Private Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Builds an Order entity from the OrderRequest DTO and cart items.
     *
     * <p>Replicates the monolith's {@code Order.initOrder(Account, Cart)} method
     * (lines 286-323) which populates all order fields from account data and cart
     * contents. In the microservice, the request DTO carries all field values
     * (addresses, payment info) and cart items are retrieved from Redis.</p>
     *
     * <h4>Field Mapping from OrderRequest</h4>
     * <ul>
     *   <li>Username, shipping/billing addresses, payment info from request</li>
     *   <li>{@code orderDate} = {@code LocalDateTime.now()} (replaces monolith's
     *       {@code new Date()} on line 288)</li>
     *   <li>{@code status} = "PENDING" (Saga initial state per AAP Section 0.7.1)</li>
     *   <li>{@code totalPrice} computed from cart items using BigDecimal reduction
     *       (matching monolith's {@code Cart.getSubTotal()}, lines 119-123)</li>
     * </ul>
     *
     * <h4>LineItem Construction</h4>
     * <p>For each cart item, a {@link LineItem} is created with:</p>
     * <ul>
     *   <li>{@code lineNum} = index + 1 (1-based, matching monolith's
     *       {@code new LineItem(lineItems.size() + 1, cartItem)} on line 327)</li>
     *   <li>{@code itemId} from {@link CartState.CartItemData#getItemId()}</li>
     *   <li>{@code quantity} from {@link CartState.CartItemData#getQuantity()}</li>
     *   <li>{@code unitPrice} from {@link CartState.CartItemData#getUnitPrice()}</li>
     * </ul>
     *
     * @param request   the order creation request with addresses and payment info
     * @param cartItems the cart items converted from Redis cart state
     * @return a fully populated Order entity ready for Saga execution
     */
    private Order buildOrderFromRequest(OrderRequest request,
                                        List<CartState.CartItemData> cartItems) {
        Order order = new Order();

        // Set username and timestamp (replicating Order.initOrder line 287-288)
        order.setUsername(request.getUsername());
        order.setOrderDate(LocalDateTime.now());
        order.setStatus("PENDING");

        // Set shipping address (replicating Order.initOrder lines 289-295)
        order.setShipToFirstName(request.getShipToFirstName());
        order.setShipToLastName(request.getShipToLastName());
        order.setShipAddress1(request.getShipAddress1());
        order.setShipAddress2(request.getShipAddress2());
        order.setShipCity(request.getShipCity());
        order.setShipState(request.getShipState());
        order.setShipZip(request.getShipZip());
        order.setShipCountry(request.getShipCountry());

        // Set billing address (replicating Order.initOrder lines 297-303)
        order.setBillToFirstName(request.getBillToFirstName());
        order.setBillToLastName(request.getBillToLastName());
        order.setBillAddress1(request.getBillAddress1());
        order.setBillAddress2(request.getBillAddress2());
        order.setBillCity(request.getBillCity());
        order.setBillState(request.getBillState());
        order.setBillZip(request.getBillZip());
        order.setBillCountry(request.getBillCountry());

        // Set payment information (replicating Order.initOrder lines 305-311)
        order.setCreditCard(request.getCreditCard());
        order.setExpiryDate(request.getExpiryDate());
        order.setCardType(request.getCardType());
        order.setCourier(request.getCourier());
        order.setLocale(request.getLocale());

        // Compute totalPrice — CRITICAL: exact BigDecimal replication of
        // monolith Cart.getSubTotal() (lines 119-123):
        //   unitPrice.multiply(new BigDecimal(quantity)) reduced with BigDecimal::add
        // Uses CartState.CartItemData.getUnitPrice() and CartItemData.getQuantity()
        BigDecimal totalPrice = cartItems.stream()
                .map(item -> item.getUnitPrice().multiply(new BigDecimal(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalPrice(totalPrice);

        // Build LineItem entities from cart items and add them to the order.
        // Uses Order.addLineItem(LineItem) — the convenience method that appends
        // to the @OneToMany collection, enabling JPA cascade on save.
        // 1-based lineNumber matching monolith: new LineItem(lineItems.size() + 1, cartItem)
        // Uses CartState.CartItemData.getItemId(), getQuantity(), getUnitPrice()
        for (int i = 0; i < cartItems.size(); i++) {
            CartState.CartItemData cartItem = cartItems.get(i);
            LineItem lineItem = new LineItem();
            lineItem.setLineNum(i + 1); // 1-based sequencing per monolith convention
            lineItem.setItemId(cartItem.getItemId());
            lineItem.setQuantity(cartItem.getQuantity());
            lineItem.setUnitPrice(cartItem.getUnitPrice());
            order.addLineItem(lineItem);
        }

        log.debug("Built order from request: username={}, totalPrice={}, lineItemCount={}",
                request.getUsername(), totalPrice, cartItems.size());
        return order;
    }

    /**
     * Converts an Order entity to an OrderDTO for REST API responses.
     *
     * <p>Maps all 27+ fields from the Order entity and its associated
     * LineItem entities to the OrderDTO structure. For each LineItem,
     * creates an {@link OrderDTO.LineItemDetail} with line number, item ID,
     * quantity, unit price, and computed total (unitPrice x quantity).</p>
     *
     * @param order the Order entity to convert
     * @return the populated OrderDTO
     */
    private OrderDTO convertToDTO(Order order) {
        OrderDTO dto = new OrderDTO();

        // Core fields
        dto.setOrderId(order.getOrderId());
        dto.setUsername(order.getUsername());
        dto.setOrderDate(order.getOrderDate());
        dto.setTotalPrice(order.getTotalPrice());
        dto.setStatus(order.getStatus());

        // Shipping address
        dto.setShipToFirstName(order.getShipToFirstName());
        dto.setShipToLastName(order.getShipToLastName());
        dto.setShipAddress1(order.getShipAddress1());
        dto.setShipAddress2(order.getShipAddress2());
        dto.setShipCity(order.getShipCity());
        dto.setShipState(order.getShipState());
        dto.setShipZip(order.getShipZip());
        dto.setShipCountry(order.getShipCountry());

        // Billing address
        dto.setBillToFirstName(order.getBillToFirstName());
        dto.setBillToLastName(order.getBillToLastName());
        dto.setBillAddress1(order.getBillAddress1());
        dto.setBillAddress2(order.getBillAddress2());
        dto.setBillCity(order.getBillCity());
        dto.setBillState(order.getBillState());
        dto.setBillZip(order.getBillZip());
        dto.setBillCountry(order.getBillCountry());

        // Payment info
        dto.setCreditCard(order.getCreditCard());
        dto.setExpiryDate(order.getExpiryDate());
        dto.setCardType(order.getCardType());
        dto.setCourier(order.getCourier());
        dto.setLocale(order.getLocale());

        // Line items — map each LineItem entity to OrderDTO.LineItemDetail
        List<OrderDTO.LineItemDetail> lineItemDetails = new ArrayList<>();
        if (order.getLineItems() != null) {
            for (LineItem lineItem : order.getLineItems()) {
                OrderDTO.LineItemDetail detail = new OrderDTO.LineItemDetail();
                detail.setLineNumber(lineItem.getLineNum());
                detail.setItemId(lineItem.getItemId());
                detail.setQuantity(lineItem.getQuantity());
                detail.setUnitPrice(lineItem.getUnitPrice());
                detail.setTotal(lineItem.getTotal()); // computed: unitPrice x quantity
                lineItemDetails.add(detail);
            }
        }
        dto.setLineItems(lineItemDetails);

        return dto;
    }
}
