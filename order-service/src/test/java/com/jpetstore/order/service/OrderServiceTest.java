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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jpetstore.order.client.AccountServiceClient;
import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.dto.CartItemDTO;
import com.jpetstore.order.dto.OrderDTO;
import com.jpetstore.order.dto.OrderRequest;
import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;
import com.jpetstore.order.repository.LineItemRepository;
import com.jpetstore.order.repository.OrderRepository;
import com.jpetstore.order.repository.OrderStatusRepository;
import com.jpetstore.order.saga.OrderSagaOrchestrator;

/**
 * Unit tests for {@link OrderService} — the Order Service's business logic layer.
 *
 * <p>This test class is modeled after the monolith's {@code OrderServiceTest.java}
 * but adapted for the new Spring Boot 3 microservice architecture:</p>
 * <ul>
 *   <li>Replaces MyBatis mapper mocks with Spring Data JPA repository mocks</li>
 *   <li>Verifies Saga orchestration delegation instead of direct mapper INSERT/UPDATE calls</li>
 *   <li>Tests externalized cart state via {@link CartStateService} instead of session-scoped Cart</li>
 *   <li>Tests user verification via {@link AccountServiceClient} REST calls</li>
 *   <li>Eliminates getNextId() tests (PostgreSQL sequence replaces shared sequence table)</li>
 *   <li>Validates BigDecimal totalPrice computation identical to monolith Cart.getSubTotal()</li>
 * </ul>
 *
 * <p>Coverage: 10 test methods across 3 public methods
 * ({@code insertOrder}, {@code getOrder}, {@code getOrdersByUsername})
 * covering happy paths, error conditions, BigDecimal arithmetic, and Saga failure behavior.</p>
 *
 * @see OrderService
 * @see OrderSagaOrchestrator
 * @see CartStateService
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    // -----------------------------------------------------------------------
    // Mock Dependencies — Matching OrderService's 7 Constructor Parameters
    // -----------------------------------------------------------------------

    /** Spring Data JPA repository for Order entities. Replaces monolith OrderMapper. */
    @Mock
    private OrderRepository orderRepository;

    /** Spring Data JPA repository for OrderStatus entities with composite PK. */
    @Mock
    private OrderStatusRepository orderStatusRepository;

    /** Spring Data JPA repository for LineItem entities with composite PK. */
    @Mock
    private LineItemRepository lineItemRepository;

    /**
     * Saga coordinator that OrderService delegates distributed order transactions to.
     * Replaces the monolith's direct mapper calls for cross-service writes.
     */
    @Mock
    private OrderSagaOrchestrator orderSagaOrchestrator;

    /**
     * Redis-backed cart state manager. Replaces monolith's session-scoped Cart object.
     */
    @Mock
    private CartStateService cartStateService;

    /** REST client for Catalog Service item lookups and inventory operations. */
    @Mock
    private CatalogServiceClient catalogServiceClient;

    /**
     * REST client for Account Service user verification.
     * Replaces monolith's session-scoped AccountActionBean lookup.
     */
    @Mock
    private AccountServiceClient accountServiceClient;

    // -----------------------------------------------------------------------
    // System Under Test
    // -----------------------------------------------------------------------

    @InjectMocks
    private OrderService orderService;

    // =======================================================================
    // insertOrder() Tests — 5 Methods
    // =======================================================================

    /**
     * Verifies the happy path for order placement:
     * <ol>
     *   <li>Account existence is verified via AccountServiceClient</li>
     *   <li>Cart contents are retrieved from CartStateService (Redis)</li>
     *   <li>An Order entity is built from OrderRequest + cart items</li>
     *   <li>The order is delegated to OrderSagaOrchestrator for distributed transaction</li>
     *   <li>Cart is cleared after successful order placement</li>
     *   <li>OrderDTO is returned with correct field mappings</li>
     * </ol>
     *
     * <p>Replaces monolith's {@code shouldCallTheMapperToInsert()} (lines 153-179),
     * which verified direct mapper INSERT calls. The new test verifies Saga delegation
     * and externalized cart clearing instead.</p>
     */
    @Test
    void shouldDelegateToSagaOrchestratorAndClearCart() {
        // Setup: Create OrderRequest with complete shipping/billing/payment data
        OrderRequest request = createOrderRequest("testuser", "session123");

        // Setup: Create CartDTO with 2 items
        // Item 1: EST-1, qty=2, unitPrice=16.50 -> subtotal=33.00
        // Item 2: EST-14, qty=1, unitPrice=58.50 -> subtotal=58.50
        // Expected totalPrice = 33.00 + 58.50 = 91.50
        CartDTO cart = createCartDTO("session123",
                createCartItemDTO("EST-1", 2, new BigDecimal("16.50")),
                createCartItemDTO("EST-14", 1, new BigDecimal("58.50")));

        // Setup: Create confirmed Order entity to be returned by saga
        Order confirmedOrder = createConfirmedOrder(1, "testuser",
                new BigDecimal("91.50"), "CONFIRMED");
        addLineItemToOrder(confirmedOrder, 1, 1, "EST-1", 2, new BigDecimal("16.50"));
        addLineItemToOrder(confirmedOrder, 1, 2, "EST-14", 1, new BigDecimal("58.50"));

        // Stub dependencies
        when(accountServiceClient.accountExists("testuser")).thenReturn(true);
        when(cartStateService.getCart("session123")).thenReturn(cart);
        when(orderSagaOrchestrator.executeOrderSaga(any(Order.class)))
                .thenReturn(confirmedOrder);

        // Execute
        OrderDTO result = orderService.insertOrder(request);

        // Verify: Account existence was checked
        verify(accountServiceClient).accountExists("testuser");

        // Verify: Cart was retrieved from Redis
        verify(cartStateService).getCart("session123");

        // Verify: Order was delegated to Saga orchestrator with correct field mapping
        verify(orderSagaOrchestrator).executeOrderSaga(argThat(order ->
                "testuser".equals(order.getUsername())
                        && order.getTotalPrice().compareTo(new BigDecimal("91.50")) == 0
                        && order.getLineItems().size() == 2
                        && "UPS".equals(order.getCourier())
                        && "CA".equals(order.getLocale())
                        && "Visa".equals(order.getCardType())
                        && "999 9999 9999 9999".equals(order.getCreditCard())
        ));

        // Verify: Cart was cleared after successful order placement
        verify(cartStateService).clearCart("session123");

        // Verify: Returned OrderDTO has correct field mappings from confirmed Order
        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(1);
        assertThat(result.getUsername()).isEqualTo("testuser");
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
        assertThat(result.getTotalPrice().compareTo(new BigDecimal("91.50"))).isZero();
        assertThat(result.getLineItems()).hasSize(2);
    }

    /**
     * Verifies that insertOrder throws {@link IllegalStateException} when the
     * account does not exist in the Account Service.
     *
     * <p>Replaces the monolith's session-based authentication check in
     * OrderActionBean with an explicit REST-based account verification.
     * The Saga must NEVER be invoked, and the cart must NOT be cleared.</p>
     */
    @Test
    void shouldThrowExceptionWhenAccountNotFound() {
        // Setup: OrderRequest for non-existent user
        OrderRequest request = createOrderRequest("unknownuser", "session123");
        when(accountServiceClient.accountExists("unknownuser")).thenReturn(false);

        // Execute and verify: IllegalStateException thrown with descriptive message
        assertThatThrownBy(() -> orderService.insertOrder(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Account not found");

        // Verify: Saga was NEVER called — no distributed transaction initiated
        verify(orderSagaOrchestrator, never()).executeOrderSaga(any());

        // Verify: Cart was NOT cleared — no side effects for invalid requests
        verify(cartStateService, never()).clearCart(anyString());
    }

    /**
     * Verifies that insertOrder throws {@link IllegalStateException} when the cart
     * is empty (no items in Redis).
     *
     * <p>In the monolith, empty cart was prevented by ActionBean flow control.
     * In the microservice, the service layer must validate cart state explicitly
     * since cart state is externalized to Redis.</p>
     */
    @Test
    void shouldThrowExceptionWhenCartIsEmpty() {
        // Setup: Valid account, but empty cart
        OrderRequest request = createOrderRequest("testuser", "session123");
        when(accountServiceClient.accountExists("testuser")).thenReturn(true);

        // Create empty CartDTO (no items)
        CartDTO emptyCart = new CartDTO();
        emptyCart.setId("session123");
        emptyCart.setItems(new ArrayList<>());
        emptyCart.setSubTotal(BigDecimal.ZERO);
        emptyCart.setNumberOfItems(0);
        when(cartStateService.getCart("session123")).thenReturn(emptyCart);

        // Execute and verify: IllegalStateException thrown for empty cart
        assertThatThrownBy(() -> orderService.insertOrder(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cart is empty");

        // Verify: Saga was NEVER called
        verify(orderSagaOrchestrator, never()).executeOrderSaga(any());

        // Verify: Cart was NOT cleared
        verify(cartStateService, never()).clearCart(anyString());
    }

    /**
     * CRITICAL: Validates that the BigDecimal totalPrice computation in the
     * microservice matches the monolith's Cart.getSubTotal() arithmetic exactly.
     *
     * <p>Monolith Cart.getSubTotal() (lines 119-123) computes:</p>
     * <pre>{@code
     * cartItemList.stream()
     *   .map(CartItem::getTotal)  // where getTotal() = listPrice * quantity
     *   .reduce(BigDecimal.ZERO, BigDecimal::add)
     * }</pre>
     *
     * <p>Microservice OrderService.buildOrderFromRequest() computes:</p>
     * <pre>{@code
     * cartItems.stream()
     *   .map(item -> item.getUnitPrice().multiply(new BigDecimal(item.getQuantity())))
     *   .reduce(BigDecimal.ZERO, BigDecimal::add)
     * }</pre>
     *
     * <p>Test data: 16.50 * 3 + 16.50 * 2 + 58.50 * 1 = 49.50 + 33.00 + 58.50 = 141.00</p>
     */
    @Test
    void shouldComputeTotalPriceUsingBigDecimalReduction() {
        // Setup: 3 items for comprehensive totalPrice verification
        OrderRequest request = createOrderRequest("testuser", "session123");

        // CartDTO with 3 items — diverse quantities for arithmetic validation
        CartDTO cart = createCartDTO("session123",
                createCartItemDTO("EST-1", 3, new BigDecimal("16.50")),
                createCartItemDTO("EST-2", 2, new BigDecimal("16.50")),
                createCartItemDTO("EST-14", 1, new BigDecimal("58.50")));

        // The confirmed order returned by saga uses the computed total
        Order confirmedOrder = createConfirmedOrder(2, "testuser",
                new BigDecimal("141.00"), "CONFIRMED");

        when(accountServiceClient.accountExists("testuser")).thenReturn(true);
        when(cartStateService.getCart("session123")).thenReturn(cart);
        when(orderSagaOrchestrator.executeOrderSaga(any(Order.class)))
                .thenReturn(confirmedOrder);

        // Execute
        OrderDTO result = orderService.insertOrder(request);

        // Verify: BigDecimal totalPrice = 16.50*3 + 16.50*2 + 58.50*1 = 141.00
        // Uses .compareTo() not .equals() for monetary comparison (scale-independent)
        verify(orderSagaOrchestrator).executeOrderSaga(argThat(order ->
                order.getTotalPrice().compareTo(new BigDecimal("141.00")) == 0
        ));

        // Verify: OrderDTO also reflects the correct total price
        assertThat(result.getTotalPrice().compareTo(new BigDecimal("141.00"))).isZero();
    }

    /**
     * Verifies that the cart is NOT cleared when the Saga orchestrator fails
     * (throws RuntimeException). This ensures the user's cart is preserved
     * for retry after a failed order placement.
     *
     * <p>In the monolith, this was an atomic transaction — the cart was only
     * cleared after all DB operations succeeded. In the microservice, the
     * Saga failure propagates as an exception before clearCart is reached,
     * preserving the user's cart for retry.</p>
     */
    @Test
    void shouldNotClearCartWhenSagaFails() {
        // Setup: Valid request and cart, but saga fails
        OrderRequest request = createOrderRequest("testuser", "session123");

        CartDTO cart = createCartDTO("session123",
                createCartItemDTO("EST-1", 1, new BigDecimal("16.50")));

        when(accountServiceClient.accountExists("testuser")).thenReturn(true);
        when(cartStateService.getCart("session123")).thenReturn(cart);
        when(orderSagaOrchestrator.executeOrderSaga(any(Order.class)))
                .thenThrow(new RuntimeException("Saga failed: inventory reservation error"));

        // Execute and verify: RuntimeException propagated from saga
        assertThatThrownBy(() -> orderService.insertOrder(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Saga failed");

        // Verify: Cart was NOT cleared — user's cart preserved for retry
        verify(cartStateService, never()).clearCart(anyString());
    }

    // =======================================================================
    // getOrder() Tests — 3 Methods
    // =======================================================================

    /**
     * Verifies that getOrder returns an OrderDTO when the order exists
     * but has no line items (edge case: order with zero line items).
     *
     * <p>Replaces monolith's {@code shouldReturnOrderWhenGivenOrderIdWithOutLineItems()}
     * (lines 62-75). Uses JPA repository mocks instead of MyBatis mapper mocks.</p>
     */
    @Test
    void shouldReturnOrderWhenGivenOrderIdWithoutLineItems() {
        // Setup: Order entity with no line items
        Order order = new Order();
        order.setOrderId(1);
        order.setUsername("testuser");
        order.setStatus("CONFIRMED");
        order.setTotalPrice(new BigDecimal("100.00"));
        order.setOrderDate(LocalDateTime.now());

        when(orderRepository.findById(1)).thenReturn(Optional.of(order));
        when(lineItemRepository.findByOrderId(1)).thenReturn(Collections.emptyList());
        when(orderStatusRepository.findByOrderId(1)).thenReturn(Collections.emptyList());

        // Execute
        OrderDTO result = orderService.getOrder(1);

        // Verify: Correct DTO field mapping
        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(1);
        assertThat(result.getUsername()).isEqualTo("testuser");
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
        assertThat(result.getTotalPrice().compareTo(new BigDecimal("100.00"))).isZero();
        assertThat(result.getLineItems()).isEmpty();
    }

    /**
     * Verifies that getOrder returns an OrderDTO with correctly mapped line items
     * including computed totals (unitPrice x quantity).
     *
     * <p>Replaces monolith's {@code shouldReturnOrderWhenGivenOrderIdExistedLineItems()}
     * (lines 78-99). The monolith test also verified inventory quantity via ItemMapper —
     * that cross-service concern is now handled by CatalogServiceClient enrichment
     * (with graceful degradation when Catalog Service is unavailable).</p>
     */
    @Test
    void shouldReturnOrderWhenGivenOrderIdWithLineItems() {
        // Setup: Order entity with 1 line item
        Order order = new Order();
        order.setOrderId(1);
        order.setUsername("testuser");
        order.setStatus("CONFIRMED");
        order.setTotalPrice(new BigDecimal("33.00"));
        order.setOrderDate(LocalDateTime.now());

        // Create LineItem: EST-1, qty=2, unitPrice=16.50
        LineItem lineItem = new LineItem();
        lineItem.setOrderId(1);
        lineItem.setLineNum(1);
        lineItem.setItemId("EST-1");
        lineItem.setQuantity(2);
        lineItem.setUnitPrice(new BigDecimal("16.50"));

        List<LineItem> lineItems = new ArrayList<>();
        lineItems.add(lineItem);

        when(orderRepository.findById(1)).thenReturn(Optional.of(order));
        when(lineItemRepository.findByOrderId(1)).thenReturn(lineItems);
        when(orderStatusRepository.findByOrderId(1)).thenReturn(Collections.emptyList());
        // Catalog enrichment returns empty — graceful degradation when Catalog Service unavailable
        when(catalogServiceClient.getItem("EST-1")).thenReturn(Optional.empty());

        // Execute
        OrderDTO result = orderService.getOrder(1);

        // Verify: Order DTO fields
        assertThat(result).isNotNull();
        assertThat(result.getOrderId()).isEqualTo(1);
        assertThat(result.getUsername()).isEqualTo("testuser");
        assertThat(result.getLineItems()).hasSize(1);

        // Verify: LineItemDetail correct mapping — total = unitPrice * quantity
        OrderDTO.LineItemDetail detail = result.getLineItems().get(0);
        assertThat(detail.getItemId()).isEqualTo("EST-1");
        assertThat(detail.getQuantity()).isEqualTo(2);
        assertThat(detail.getUnitPrice().compareTo(new BigDecimal("16.50"))).isZero();
        // Total = unitPrice * quantity = 16.50 * 2 = 33.00
        assertThat(detail.getTotal().compareTo(new BigDecimal("33.00"))).isZero();
    }

    /**
     * Verifies that getOrder throws {@link RuntimeException} when the order ID
     * does not exist in the repository.
     *
     * <p>In the microservice, missing orders result in an explicit exception
     * rather than returning null — providing consistent error handling
     * for REST API error responses.</p>
     */
    @Test
    void shouldThrowExceptionWhenOrderNotFound() {
        // Setup: No order found for ID 999
        when(orderRepository.findById(999)).thenReturn(Optional.empty());

        // Execute and verify: RuntimeException with descriptive message
        assertThatThrownBy(() -> orderService.getOrder(999))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Order not found");
    }

    // =======================================================================
    // getOrdersByUsername() Tests — 2 Methods
    // =======================================================================

    /**
     * Verifies that getOrdersByUsername returns a list of OrderDTOs
     * for a user with existing orders, ordered by date descending.
     *
     * <p>Replaces monolith's {@code shouldReturnOrderList()} (lines 101-115).
     * Uses Spring Data JPA derived query method
     * {@code findByUsernameOrderByOrderDateDesc} instead of MyBatis mapper.</p>
     */
    @Test
    void shouldReturnOrderList() {
        // Setup: 2 orders for the user, ordered by date descending
        Order order1 = new Order();
        order1.setOrderId(1);
        order1.setUsername("testuser");
        order1.setStatus("CONFIRMED");
        order1.setTotalPrice(new BigDecimal("50.00"));
        order1.setOrderDate(LocalDateTime.now());

        Order order2 = new Order();
        order2.setOrderId(2);
        order2.setUsername("testuser");
        order2.setStatus("CONFIRMED");
        order2.setTotalPrice(new BigDecimal("75.00"));
        order2.setOrderDate(LocalDateTime.now().minusDays(1));

        List<Order> orders = new ArrayList<>();
        orders.add(order1);
        orders.add(order2);

        when(orderRepository.findByUsernameOrderByOrderDateDesc("testuser"))
                .thenReturn(orders);

        // Execute
        List<OrderDTO> result = orderService.getOrdersByUsername("testuser");

        // Verify: Correct list size and order DTO mapping
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getOrderId()).isEqualTo(1);
        assertThat(result.get(0).getUsername()).isEqualTo("testuser");
        assertThat(result.get(0).getTotalPrice().compareTo(new BigDecimal("50.00"))).isZero();
        assertThat(result.get(1).getOrderId()).isEqualTo(2);
        assertThat(result.get(1).getUsername()).isEqualTo("testuser");
        assertThat(result.get(1).getTotalPrice().compareTo(new BigDecimal("75.00"))).isZero();

        // Verify: Repository called with correct username
        verify(orderRepository).findByUsernameOrderByOrderDateDesc("testuser");
    }

    /**
     * Verifies that getOrdersByUsername returns an empty list when the user
     * has no orders — not null, not an exception, just an empty list.
     */
    @Test
    void shouldReturnEmptyListWhenNoOrdersForUser() {
        // Setup: No orders found for "newuser"
        when(orderRepository.findByUsernameOrderByOrderDateDesc("newuser"))
                .thenReturn(Collections.emptyList());

        // Execute
        List<OrderDTO> result = orderService.getOrdersByUsername("newuser");

        // Verify: Empty list returned (not null, not exception)
        assertThat(result).isEmpty();
        verify(orderRepository).findByUsernameOrderByOrderDateDesc("newuser");
    }

    // =======================================================================
    // Helper Methods — Test Data Factories
    // =======================================================================

    /**
     * Creates an {@link OrderRequest} with complete shipping, billing, and payment data.
     *
     * <p>Mirrors the monolith's {@code Order.initOrder(Account, Cart)} field copying,
     * providing all 23 fields needed for the OrderService to build an Order entity.</p>
     *
     * @param username       the username for account verification
     * @param cartSessionId  the Redis cart session ID
     * @return a fully populated OrderRequest
     */
    private OrderRequest createOrderRequest(String username, String cartSessionId) {
        OrderRequest request = new OrderRequest();
        request.setUsername(username);
        request.setCartSessionId(cartSessionId);

        // Shipping address
        request.setShipToFirstName("John");
        request.setShipToLastName("Doe");
        request.setShipAddress1("123 Main St");
        request.setShipCity("Springfield");
        request.setShipState("IL");
        request.setShipZip("62704");
        request.setShipCountry("USA");

        // Billing address
        request.setBillToFirstName("John");
        request.setBillToLastName("Doe");
        request.setBillAddress1("123 Main St");
        request.setBillCity("Springfield");
        request.setBillState("IL");
        request.setBillZip("62704");
        request.setBillCountry("USA");

        // Payment information (matching monolith simulated payment logic)
        request.setCreditCard("999 9999 9999 9999");
        request.setExpiryDate("12/03");
        request.setCardType("Visa");

        // Courier and locale
        request.setCourier("UPS");
        request.setLocale("CA");

        return request;
    }

    /**
     * Creates a {@link CartDTO} with the specified items.
     *
     * <p>Computes subTotal as the sum of each item's unitPrice * quantity,
     * matching monolith Cart.getSubTotal() computation pattern:
     * {@code stream().map(CartItem::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add)}</p>
     *
     * @param sessionId the Redis session ID
     * @param cartItems the cart items to include
     * @return a CartDTO with computed subtotal
     */
    private CartDTO createCartDTO(String sessionId, CartItemDTO... cartItems) {
        CartDTO cart = new CartDTO();
        cart.setId(sessionId);
        List<CartItemDTO> items = new ArrayList<>();
        BigDecimal subTotal = BigDecimal.ZERO;
        for (CartItemDTO item : cartItems) {
            items.add(item);
            subTotal = subTotal.add(
                    item.getUnitPrice().multiply(new BigDecimal(item.getQuantity())));
        }
        cart.setItems(items);
        cart.setSubTotal(subTotal);
        cart.setNumberOfItems(items.size());
        return cart;
    }

    /**
     * Creates a {@link CartItemDTO} with the specified fields.
     * Computes total as unitPrice * quantity, mirroring monolith CartItem.getTotal().
     *
     * @param itemId    the catalog item ID (e.g., "EST-1")
     * @param quantity  the quantity in cart
     * @param unitPrice the unit price as BigDecimal
     * @return a fully populated CartItemDTO
     */
    private CartItemDTO createCartItemDTO(String itemId, int quantity,
                                          BigDecimal unitPrice) {
        CartItemDTO item = new CartItemDTO();
        item.setItemId(itemId);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice);
        item.setInStock(true);
        item.setTotal(unitPrice.multiply(new BigDecimal(quantity)));
        return item;
    }

    /**
     * Creates a confirmed {@link Order} entity for use as a saga return value.
     *
     * @param orderId    the order ID (set by PostgreSQL sequence in production)
     * @param username   the ordering user's username
     * @param totalPrice the computed total price
     * @param status     the order status ("CONFIRMED", "FAILED", "PENDING")
     * @return a fully populated Order entity
     */
    private Order createConfirmedOrder(int orderId, String username,
                                       BigDecimal totalPrice, String status) {
        Order order = new Order();
        order.setOrderId(orderId);
        order.setUsername(username);
        order.setTotalPrice(totalPrice);
        order.setStatus(status);
        order.setOrderDate(LocalDateTime.now());
        order.setCourier("UPS");
        order.setLocale("CA");
        order.setCreditCard("999 9999 9999 9999");
        order.setExpiryDate("12/03");
        order.setCardType("Visa");
        return order;
    }

    /**
     * Adds a {@link LineItem} to an {@link Order} entity for test data construction.
     *
     * @param order     the order to add the line item to
     * @param orderId   the order ID for the line item
     * @param lineNum   the 1-based line number within the order
     * @param itemId    the catalog item ID (e.g., "EST-1")
     * @param quantity  the quantity ordered
     * @param unitPrice the unit price per item
     */
    private void addLineItemToOrder(Order order, int orderId, int lineNum,
                                    String itemId, int quantity,
                                    BigDecimal unitPrice) {
        LineItem lineItem = new LineItem();
        lineItem.setOrderId(orderId);
        lineItem.setLineNum(lineNum);
        lineItem.setItemId(itemId);
        lineItem.setQuantity(quantity);
        lineItem.setUnitPrice(unitPrice);
        order.addLineItem(lineItem);
    }
}
